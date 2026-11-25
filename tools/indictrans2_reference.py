"""
Reference (Python) implementation of the on-device IndicTrans2 pipeline used by the app.
The Kotlin port (app/.../ml/translation/IndicTrans2Engine.kt) mirrors these steps one for one,
and its unit test compares against outputs produced here.

  en -> indic : models/packs/mt/en-indic   (SentencePiece-BPE by score, encoder emits cross K/V)
  indic -> en : models/packs/mt/indic-en   (BPE by merge rank, HF-optimum decoder + decoder_with_past)
  indic -> indic : pivot through English

Usage: python tools/indictrans2_reference.py            (prints a translation matrix)
Requires: pip install onnxruntime numpy
"""
import json
import os
import re
import sys
import time
import unicodedata

import numpy as np
import onnxruntime as ort

MT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "models", "packs", "mt")

FLORES = {"en": "eng_Latn", "hi": "hin_Deva", "mr": "mar_Deva", "bn": "ben_Beng", "pa": "pan_Guru",
          "gu": "guj_Gujr", "or": "ory_Orya", "ta": "tam_Taml", "te": "tel_Telu", "kn": "kan_Knda",
          "ml": "mal_Mlym"}
SCRIPT_BASE = {"hi": 0x0900, "mr": 0x0900, "bn": 0x0980, "pa": 0x0A00, "gu": 0x0A80, "or": 0x0B00,
               "ta": 0x0B80, "te": 0x0C00, "kn": 0x0C80, "ml": 0x0D00}
DEVANAGARI = {"hi", "mr"}


# ------------------------------------------------------------------ script conversion (indic-nlp)
def _tamil_offset(off: int) -> int:
    # Tamil lacks voiced/aspirated plosives: map them to the unvoiced unaspirated letter of the row
    if 0x15 <= off <= 0x28 and off != 0x1C and not ((off - 0x15) % 5 == 0 or (off - 0x15) % 5 == 4):
        off = 0x15 + 5 * ((off - 0x15) // 5)
    if off in (0x2B, 0x2C, 0x2D):
        off = 0x2A
    if off == 0x36:
        off = 0x37
    return off


def transliterate(text: str, src: str, tgt: str) -> str:
    if src == tgt or src not in SCRIPT_BASE or tgt not in SCRIPT_BASE:
        return text
    out = []
    for c in text:
        off = ord(c) - SCRIPT_BASE[src]
        if 0 <= off <= 0x6F and c not in "।॥":
            if tgt == "ta":
                off = _tamil_offset(off)
            out.append(chr(SCRIPT_BASE[tgt] + off))
        else:
            out.append(c)
    return "".join(out)


# ------------------------------------------------------------------ text normalisation
_PUNCT = r"([.,!?;:\"()\[\]{}।॥|])"
_INDIC_DIGITS = {chr(base + 0x66 + d): str(d) for base in set(SCRIPT_BASE.values()) for d in range(10)}


def punct_norm(text: str) -> str:
    text = (text.replace("“", '"').replace("”", '"').replace("‘", "'").replace("’", "'")
                .replace("–", "-").replace("—", " - ").replace("…", "..."))
    return re.sub(r"\s+", " ", text).strip()


def tokenize_en(text: str) -> str:
    text = re.sub(_PUNCT, r" \1 ", text)
    text = re.sub(r"(\w)'(\w)", r"\1 '\2", text)  # Moses: don't -> don 't
    return re.sub(r"\s+", " ", text).strip()


def tokenize_indic(text: str) -> str:
    text = "".join(_INDIC_DIGITS.get(c, c) for c in text)
    text = unicodedata.normalize("NFD", text)       # split nukta letters (ड़ -> ड + ़)
    text = re.sub(_PUNCT, r" \1 ", text)
    return re.sub(r"\s+", " ", text).strip()


def detok(text: str) -> str:
    text = re.sub(r"\s+([.,!?;:)\]}।॥])", r"\1", text)
    text = re.sub(r"([(\[{])\s+", r"\1", text)
    text = re.sub(r"(\w) '(\w)", r"\1'\2", text)
    return re.sub(r"\s+", " ", text).strip()


# ------------------------------------------------------------------ BPE helpers
def bpe(word, rank_of):
    """Greedy BPE: repeatedly merge the adjacent pair with the best (lowest) rank."""
    sym = list(word)
    while len(sym) > 1:
        best, bi = None, -1
        for i in range(len(sym) - 1):
            r = rank_of(sym[i], sym[i + 1])
            if r is not None and (best is None or r < best):
                best, bi = r, i
        if bi < 0:
            break
        sym[bi:bi + 2] = [sym[bi] + sym[bi + 1]]
    return sym


def argmax_step(logits):
    return int(np.argmax(logits.reshape(-1)))


# ------------------------------------------------------------------ en -> indic
class EnIndic:
    def __init__(self):
        d = os.path.join(MT, "en-indic")
        v = json.load(open(os.path.join(d, "vocab.json"), encoding="utf-8"))
        self.meta = v["meta"]
        self.score = {p: s for p, s, t in zip(v["src_pieces"], v["src_scores"], v["src_types"]) if t == 1}
        self.src_dict = v["src_dict"]
        self.tgt = v["tgt_tokens"]
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 2
        self.enc = ort.InferenceSession(os.path.join(d, "encoder.onnx"), opts, providers=["CPUExecutionProvider"])
        self.dec = ort.InferenceSession(os.path.join(d, "decoder.onnx"), opts, providers=["CPUExecutionProvider"])
        self.layers = self.meta["layers"]

    def _rank(self, a, b):
        s = self.score.get(a + b)
        return None if s is None else -s  # higher score merges first

    def encode(self, text, tgt):
        text = unicodedata.normalize("NFKC", tokenize_en(punct_norm(text)))
        ids = [self.src_dict["eng_Latn"], self.src_dict[FLORES[tgt]]]
        for w in text.split(" "):
            for piece in bpe("▁" + w, self._rank):
                ids.append(self.src_dict.get(piece, 3))
        return ids + [2]

    def translate(self, text, tgt):
        ids = np.array([self.encode(text, tgt)], dtype=np.int64)
        mask = np.ones_like(ids)
        enc_out = self.enc.run(None, {"input_ids": ids, "attention_mask": mask})
        names = [o.name for o in self.enc.get_outputs()]
        e = dict(zip(names, enc_out))
        self_kv = {}
        for i in range(self.layers):
            self_kv[f"self_k_{i}"] = e["empty_cache"]
            self_kv[f"self_v_{i}"] = e["empty_cache"]
        out_ids, tok = [], self.meta["decoder_start_token_id"]
        for _ in range(int(ids.shape[1] * 2 + 10)):
            feed = {"input_ids": np.array([[tok]], dtype=np.int64), "encoder_attention_mask": mask}
            feed.update(self_kv)
            for i in range(self.layers):
                feed[f"cross_k_{i}"] = e[f"cross_k_{i}"]
                feed[f"cross_v_{i}"] = e[f"cross_v_{i}"]
            res = dict(zip([o.name for o in self.dec.get_outputs()], self.dec.run(None, feed)))
            tok = argmax_step(res["logits"])
            if tok == self.meta["eos_token_id"]:
                break
            out_ids.append(tok)
            for i in range(self.layers):
                self_kv[f"self_k_{i}"] = res[f"present_k_{i}"]
                self_kv[f"self_v_{i}"] = res[f"present_v_{i}"]
        deva = "".join(self.tgt[t] for t in out_ids if t > 3).replace("▁", " ").strip()
        out = transliterate(deva, "hi", tgt)
        if tgt == "or":
            out = out.replace("ଯ଼", "ୟ")  # IndicProcessor's Odia fix
        return detok(out)


# ------------------------------------------------------------------ indic -> en
class IndicEn:
    def __init__(self):
        d = os.path.join(MT, "indic-en")
        self.meta = json.load(open(os.path.join(d, "it2_meta.json"), encoding="utf-8"))
        self.vocab = {}
        with open(os.path.join(d, "src_vocab.txt"), encoding="utf-8") as f:
            for i, line in enumerate(f):
                self.vocab.setdefault(line.rstrip("\n"), i)
        self.merges = {}
        with open(os.path.join(d, "src_merges.txt"), encoding="utf-8") as f:
            for i, line in enumerate(f):
                parts = line.rstrip("\n").split(" ")
                if len(parts) == 2:
                    self.merges.setdefault((parts[0], parts[1]), i)
        with open(os.path.join(d, "tgt_vocab.txt"), encoding="utf-8") as f:
            self.tgt = [l.rstrip("\n") for l in f]
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 2
        mk = lambda n: ort.InferenceSession(os.path.join(d, n), opts, providers=["CPUExecutionProvider"])
        self.enc, self.dec0, self.dec = mk("encoder_model.onnx"), mk("decoder_model.onnx"), mk("decoder_with_past_model.onnx")

    def encode(self, text, src):
        if src not in DEVANAGARI:
            text = transliterate(text, src, "hi")
        text = tokenize_indic(punct_norm(text))
        sp = self.meta["src_special"]
        ids = [sp[FLORES[src]], sp["eng_Latn"]]
        for w in text.split(" "):
            w = unicodedata.normalize("NFKC", w)
            for piece in bpe("▁" + w, lambda a, b: self.merges.get((a, b))):
                i = self.vocab.get(piece, self.meta["unk_id"])
                ids.append(i if i < self.meta["src_dict_size"] else self.meta["unk_id"])
        return ids + [self.meta["eos_id"]]

    def translate(self, text, src):
        ids = np.array([self.encode(text, src)], dtype=np.int64)
        mask = np.ones_like(ids)
        hidden = self.enc.run(None, {"input_ids": ids, "attention_mask": mask})[0]
        tok = self.meta["decoder_start_id"]
        res = dict(zip([o.name for o in self.dec0.get_outputs()],
                       self.dec0.run(None, {"input_ids": np.array([[tok]], dtype=np.int64),
                                            "encoder_attention_mask": mask, "encoder_hidden_states": hidden})))
        out_ids = []
        for _ in range(int(ids.shape[1] * 2 + 10)):
            tok = argmax_step(res["logits"][:, -1, :])
            if tok == self.meta["eos_id"]:
                break
            out_ids.append(tok)
            feed = {"input_ids": np.array([[tok]], dtype=np.int64), "encoder_attention_mask": mask}
            for k, v in res.items():
                if k.startswith("present."):
                    feed["past_key_values." + k[len("present."):]] = v
            out = dict(zip([o.name for o in self.dec.get_outputs()], self.dec.run(None, feed)))
            # decoder_with_past returns only the self-attention cache; keep the encoder cache
            for k, v in out.items():
                res[k] = v
        text = "".join(self.tgt[t] for t in out_ids if t > 3).replace("▁", " ").strip()
        return detok(text)


def main():
    en_in, in_en = EnIndic(), IndicEn()
    tests_en = ["We need food and water", "The bridge near the school has collapsed",
                "My grandmother has diabetes and needs insulin tonight"]
    tests_in = {"ta": "எங்களுக்கு உணவும் தண்ணீரும் தேவை", "hi": "पुल के पास चिकित्सा दल की जरूरत है"}
    targets = ["hi", "ta", "te", "kn", "ml", "bn", "mr", "gu", "or", "pa"]
    for s in tests_en:
        print(f"\n=== [en] {s}")
        for t in targets:
            t0 = time.time()
            print(f"  {t}: {en_in.translate(s, t)}   ({(time.time() - t0) * 1000:.0f} ms)")
    for src, s in tests_in.items():
        print(f"\n=== [{src}] {s}")
        t0 = time.time()
        en = in_en.translate(s, src)
        print(f"  en: {en}   ({(time.time() - t0) * 1000:.0f} ms)")
        for t in ["hi", "ta", "te", "bn"]:
            if t != src:
                print(f"  {t} (via en): {en_in.translate(en, t)}")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    main()
