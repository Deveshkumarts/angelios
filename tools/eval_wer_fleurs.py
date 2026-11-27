"""
Real-speech STT benchmark for the on-device models, on Google FLEURS (read speech by real
speakers, human transcripts, CC-BY-4.0): https://huggingface.co/datasets/google/fleurs

For each language it streams the FLEURS test archive and keeps only the first N clips (the rest of
the multi-hundred-MB archive is never downloaded), caches them under models/benchmark/fleurs/,
transcribes them with exactly the model files the phone uses (models/packs/<lang>/stt) and reports
WER, CER and real-time factor. Results go to docs/benchmark_fleurs.md and .json.

  python tools/eval_wer_fleurs.py                # all languages that have an STT pack, 50 clips each
  python tools/eval_wer_fleurs.py hi ta --n 100

Notes: FLEURS sentences are read Wikipedia text, so vocabulary is much harder than field messages
but the audio is cleaner than a phone in the open. Clips are recognised whole (no VAD split).
Requires: pip install sherpa-onnx numpy
"""
import csv
import io
import json
import os
import re
import sys
import tarfile
import time
import unicodedata
import urllib.request

import numpy as np
import sherpa_onnx

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
PACKS = os.path.join(ROOT, "models", "packs")
CACHE = os.path.join(ROOT, "models", "benchmark", "fleurs")
DOCS = os.path.join(ROOT, "docs")
BASE = "https://huggingface.co/datasets/google/fleurs/resolve/main/data"

FLEURS = {"hi": "hi_in", "ta": "ta_in", "te": "te_in", "kn": "kn_in", "ml": "ml_in", "bn": "bn_in",
          "mr": "mr_in", "gu": "gu_in", "or": "or_in", "pa": "pa_in", "en": "en_us"}
NAMES = {"hi": "Hindi", "ta": "Tamil", "te": "Telugu", "kn": "Kannada", "ml": "Malayalam", "bn": "Bengali",
         "mr": "Marathi", "gu": "Gujarati", "or": "Odia", "pa": "Punjabi", "en": "English"}


def http(url: str):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "angelios-benchmark"}), timeout=120)


def fetch_clips(lang: str, n: int):
    """Returns [(wav_path, reference_text)], downloading only as much of the archive as needed."""
    cfg = FLEURS[lang]
    out_dir = os.path.join(CACHE, cfg)
    os.makedirs(out_dir, exist_ok=True)
    tsv_path = os.path.join(out_dir, "test.tsv")
    if not os.path.exists(tsv_path):
        with http(f"{BASE}/{cfg}/test.tsv") as r, open(tsv_path, "wb") as f:
            f.write(r.read())
    refs = {}
    with open(tsv_path, encoding="utf-8") as f:
        for row in csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
            if len(row) >= 4:
                refs[row[1]] = row[3] or row[2]   # normalised transcription, else raw
    have = [w for w in sorted(os.listdir(out_dir)) if w.endswith(".wav") and w in refs]
    if len(have) < n:
        print(f"  streaming {cfg}/audio/test.tar.gz for {n - len(have)} more clips...", flush=True)
        with http(f"{BASE}/{cfg}/audio/test.tar.gz") as r:
            with tarfile.open(fileobj=r, mode="r|gz") as tar:
                for m in tar:
                    name = os.path.basename(m.name)
                    if not m.isfile() or name not in refs or name in have:
                        continue
                    data = tar.extractfile(m).read()
                    with open(os.path.join(out_dir, name), "wb") as f:
                        f.write(data)
                    have.append(name)
                    if len(have) >= n:
                        break   # stop reading: the rest of the archive is never downloaded
    return [(os.path.join(out_dir, w), refs[w]) for w in have[:n]]


def read_wav(path):
    """Minimal RIFF reader: PCM16/32 or IEEE float32 (FLEURS uses float), any rate, mono-mixed."""
    import struct
    data = open(path, "rb").read()
    pos, fmt, rate, ch, bits, pcm = 12, 1, 16000, 1, 16, b""
    while pos + 8 <= len(data):
        cid, size = data[pos:pos + 4], struct.unpack("<I", data[pos + 4:pos + 8])[0]
        body = data[pos + 8:pos + 8 + size]
        if cid == b"fmt ":
            fmt, ch, rate = struct.unpack("<HHI", body[:8])
            bits = struct.unpack("<H", body[14:16])[0]
        elif cid == b"data":
            pcm = body
        pos += 8 + size + (size & 1)
    if fmt == 3:
        x = np.frombuffer(pcm, dtype=np.float32 if bits == 32 else np.float64).astype(np.float32)
    else:
        x = np.frombuffer(pcm, dtype={16: np.int16, 32: np.int32}[bits]).astype(np.float32) / (2 ** (bits - 1))
    if ch > 1:
        x = x.reshape(-1, ch).mean(axis=1)
    if rate != 16000:
        n = int(len(x) * 16000 / rate)
        x = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)
    return x


_ONES = ("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen "
         "sixteen seventeen eighteen nineteen").split()
_TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()


def _en_words(n: int) -> str:
    if n < 20:
        return _ONES[n]
    if n < 100:
        return _TENS[n // 10] + ("" if n % 10 == 0 else " " + _ONES[n % 10])
    if n < 1000:
        return _ONES[n // 100] + " hundred" + ("" if n % 100 == 0 else " " + _en_words(n % 100))
    if n < 1_000_000:
        return _en_words(n // 1000) + " thousand" + ("" if n % 1000 == 0 else " " + _en_words(n % 1000))
    return str(n)


def _indic_number_words(lang):
    """0-99 words for the reference side, same source as the app (tools/gen_number_words.py)."""
    try:
        from num_to_words import num_to_word
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        from gen_number_words import HINDI_STANDARD
    except ImportError:
        return None
    return HINDI_STANDARD if lang == "hi" else [num_to_word(n, lang) for n in range(100)]


_WORDS_CACHE = {}


def normalize(text: str, lang: str = "") -> str:
    if lang and lang != "en":
        # References write "25", the recogniser writes the word: score them as the same (0-99)
        words = _WORDS_CACHE.setdefault(lang, _indic_number_words(lang))
        if words:
            text = re.sub(r"(?<!\d)\d{1,2}(?!\d)", lambda m: " " + words[int(m.group())] + " ", text)
    if lang == "en":
        # References write "25", the recogniser writes "twenty five": score them as the same words
        text = re.sub(r"\d+", lambda m: " " + _en_words(int(m.group())) + " ", text.replace(",", ""))
    text = unicodedata.normalize("NFC", text.lower()).replace("‌", "").replace("‍", "")
    text = "".join(" " if unicodedata.category(c)[0] in "PS" else c for c in text)
    return " ".join(text.split())


def edit_distance(a, b):
    d = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(b) + 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (a[i - 1] != b[j - 1]))
    return d[len(b)]


def classify_errors(ref, hyp):
    """Aligns words; counts substitutions that are near misses (<= 2 characters apart: a suffix or
    vowel sign) vs everything else (wrong words, insertions, deletions)."""
    n, m = len(ref), len(hyp)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        d[i][0] = i
    for j in range(m + 1):
        d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]))
    near = far = 0
    i, j = n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and d[i][j] == d[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]):
            if ref[i - 1] != hyp[j - 1]:
                if edit_distance(ref[i - 1], hyp[j - 1]) <= 2:
                    near += 1
                else:
                    far += 1
            i, j = i - 1, j - 1
        elif i > 0 and d[i][j] == d[i - 1][j] + 1:
            far += 1
            i -= 1
        else:
            far += 1
            j -= 1
    return near, far


_BLOCKS = (0x0900, 0x0980, 0x0A00, 0x0A80, 0x0B00, 0x0B80, 0x0C00, 0x0C80, 0x0D00)


def _block(c):
    return next((b for b in _BLOCKS if 0 <= ord(c) - b <= 0x7F), None)


def _vowel_sign(c):
    b = _block(c)
    if b is None:
        return False
    off = ord(c) - b
    return 0x3E <= off <= 0x4C or 0x55 <= off <= 0x57 or off in (0x62, 0x63)


def cleanup(text):
    """Same as the app's TranscriptCleanup: drop a virama right before a vowel sign, and a vowel
    sign repeated twice in a row (artefacts of gluing sub-word pieces)."""
    out = []
    for i, c in enumerate(text):
        nxt = text[i + 1] if i + 1 < len(text) else None
        b = _block(c)
        if b is not None and ord(c) - b == 0x4D and nxt and _vowel_sign(nxt) and _block(nxt) == b:
            continue
        if _vowel_sign(c) and out and out[-1] == c:
            continue
        out.append(c)
    return "".join(out)


def recognizer(lang):
    base = os.path.join(PACKS, lang, "stt")
    return sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=os.path.join(base, "model.int8.onnx"), tokens=os.path.join(base, "tokens.txt"),
        num_threads=2, decoding_method="greedy_search")


def run(lang, n):
    clips = fetch_clips(lang, n)
    asr = recognizer(lang)
    w_err = w_tot = c_err = c_tot = 0
    near = far = 0
    clean_err = clean_tot = mismatched = 0
    audio = decode = 0.0
    examples = []
    for path, ref in clips:
        x = read_wav(path)
        st = asr.create_stream()
        st.accept_waveform(16000, x)
        t0 = time.time()
        asr.decode_stream(st)
        decode += time.time() - t0
        audio += len(x) / 16000
        r, h = normalize(ref, lang), normalize(cleanup(st.result.text), lang)
        e = edit_distance(r.split(), h.split())
        w_err += e
        w_tot += len(r.split())
        # A reference covering only part of the audio (seen in FLEURS) shows up as a hypothesis far
        # longer than the reference; report WER with those clips set aside as well.
        if len(h.split()) > 2 * len(r.split()) + 5:
            mismatched += 1
        else:
            clean_err += e
            clean_tot += len(r.split())
        c_err += edit_distance(r.replace(" ", ""), h.replace(" ", ""))
        n_, f_ = classify_errors(r.split(), h.split())
        near += n_
        far += f_
        c_tot += len(r.replace(" ", ""))
        if len(examples) < 2:
            examples.append((ref, st.result.text))
    return {"lang": lang, "name": NAMES[lang], "clips": len(clips), "audio_min": round(audio / 60, 1),
            "wer": round(100 * w_err / max(1, w_tot), 1), "cer": round(100 * c_err / max(1, c_tot), 1),
            "rtf": round(decode / max(audio, 1e-9), 3),
            "near_miss_pct": round(100 * near / max(1, near + far), 1),
            "wer_excl_mismatched": round(100 * clean_err / max(1, clean_tot), 1), "mismatched_refs": mismatched,
            "examples": examples}


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = sys.argv[1:]
    n = int(args[args.index("--n") + 1]) if "--n" in args else 50
    langs = [a for a in args if a in FLEURS] or [l for l in FLEURS
                                                  if os.path.exists(os.path.join(PACKS, l, "stt", "model.int8.onnx"))]
    results = []
    for lang in langs:
        print(f"[{lang}] {NAMES[lang]}", flush=True)
        res = run(lang, n)
        results.append(res)
        print(f"  WER {res['wer']}%  CER {res['cer']}%  RTF {res['rtf']}  ({res['clips']} clips, {res['audio_min']} min)")
        for ref, hyp in res["examples"]:
            print(f"    ref: {ref}\n    hyp: {hyp}")
    os.makedirs(DOCS, exist_ok=True)
    with open(os.path.join(DOCS, "benchmark_fleurs.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    lines = ["# On-device STT on real speech (Google FLEURS test set)", "",
             f"Models: the exact `models/packs/<lang>/stt` files the phone runs (AI4Bharat IndicConformer CTC int8;"
             f" English: NeMo FastConformer CTC int8), sherpa-onnx, greedy decoding, 2 CPU threads (laptop).",
             f"Data: first {n} clips of each FLEURS test split (read Wikipedia sentences, real speakers).",
             "WER/CER after lower-casing and removing punctuation. CER is the fairer figure for agglutinative",
             "languages (Tamil, Telugu, Kannada, Malayalam), where one word carries a whole phrase.", "",
             "Numbers 0-99 written as digits in a reference are converted to words before scoring (the",
             "recogniser writes words). Near-miss = share of word errors only 1-2 characters off",
             "(a suffix or vowel sign) rather than a wrong word.", "",
             "Transcripts get the app's TranscriptCleanup. \"WER excl.\" sets aside clips whose reference",
             "covers only part of the audio (hypothesis > 2x reference + 5 words; count in brackets).", "",
             "| Language | Clips | Audio (min) | WER % | WER excl. % | CER % | Near-miss % of errors | RTF |",
             "|---|---|---|---|---|---|---|---|"]
    for r in results:
        lines.append(f"| {r['name']} | {r['clips']} | {r['audio_min']} | {r['wer']} | "
                     f"{r['wer_excl_mismatched']} ({r['mismatched_refs']}) | {r['cer']} | "
                     f"{r['near_miss_pct']} | {r['rtf']} |")
    lines += ["", "Reproduce: `python tools/eval_wer_fleurs.py` (downloads ~20-40 MB of clips per language)."]
    with open(os.path.join(DOCS, "benchmark_fleurs.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n" + "\n".join(l for l in lines if l.startswith("|")))


if __name__ == "__main__":
    main()
