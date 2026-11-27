"""
Download the open-source, on-device speech models used by the Angelios app.

  STT : AI4Bharat IndicConformer (CTC, int8) exported for sherpa-onnx      (MIT / Apache-2.0 export)
        English: NVIDIA NeMo fast-conformer CTC (int8)                      (CC-BY-4.0)
        https://huggingface.co/NiranjanYadavIN/indicconformer-sherpa-onnx
  TTS : Meta MMS-TTS (VITS) exported for sherpa-onnx                        (CC-BY-NC-4.0)
        https://huggingface.co/willwade/mms-tts-multilingual-models-onnx
  VAD : Silero VAD (sherpa-onnx release asset)                              (MIT)
  MT  : AI4Bharat IndicTrans2 distilled 200M, int8 ONNX (optional "mt" pack)  (MIT)
        en->indic: https://huggingface.co/Aji230923/indictrans2-en-indic-dist-200M-onnx
        indic->en: https://huggingface.co/ngiftsun/indictrans2-indic-en-int8-mobile

Files land in  models/packs/<lang>/{stt,tts}/  and  models/packs/common/ , the same layout
INSTALL_LANGUAGE_PACKS.bat copies to the phone. Nothing is downloaded at app run time.

Usage:  python tools/download_language_packs.py hi ta en      (or: all)
        python tools/download_language_packs.py mt            (offline translation pack, ~610 MB)
        python tools/download_language_packs.py --stt-only te kn   (recognition models only, e.g. for benchmarks)
"""
import json
import os
import sys
import urllib.request

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "models", "packs")

STT_REPO = "https://huggingface.co/NiranjanYadavIN/indicconformer-sherpa-onnx/resolve/main"
TTS_REPO = "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main"
VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

# app language code -> MMS (ISO 639-3) code
LANGS = {
    "en": "eng", "hi": "hin", "ta": "tam", "te": "tel", "kn": "kan",
    "ml": "mal", "mr": "mar", "gu": "guj", "bn": "ben", "or": "ory",
    "pa": "pan",  # Punjabi: extra language beyond the 10 required
}


def fetch(url: str, dest: str) -> None:
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print(f"  ok   {os.path.relpath(dest, ROOT)} (already present)")
        return
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    part = dest + ".part"
    print(f"  get  {os.path.relpath(dest, ROOT)}", flush=True)
    req = urllib.request.Request(url, headers={"User-Agent": "angelios-pack-downloader"})
    with urllib.request.urlopen(req, timeout=60) as r, open(part, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
        if total and done != total:
            raise IOError(f"incomplete download for {url}: {done}/{total} bytes")
    os.replace(part, dest)


MT_EN_INDIC = "https://huggingface.co/Aji230923/indictrans2-en-indic-dist-200M-onnx/resolve/main"
MT_INDIC_EN = "https://huggingface.co/ngiftsun/indictrans2-indic-en-int8-mobile/resolve/main"


def download_mt() -> None:
    base = os.path.join(ROOT, "mt")
    print("[mt]")
    for f in ("encoder.onnx", "decoder.onnx", "vocab.json"):
        fetch(f"{MT_EN_INDIC}/{f}", os.path.join(base, "en-indic", f))
    for f in ("encoder_model.onnx", "decoder_model.onnx", "decoder_with_past_model.onnx",
              "src_vocab.txt", "src_merges.txt", "tgt_vocab.txt", "it2.properties", "it2_meta.json"):
        fetch(f"{MT_INDIC_EN}/{f}", os.path.join(base, "indic-en", f))
    with open(os.path.join(base, "pack.json"), "w", encoding="utf-8") as fh:
        json.dump({"pack": "mt", "model": "IndicTrans2 distilled 200M int8"}, fh)


def download(lang: str, stt_only: bool = False) -> None:
    mms = LANGS[lang]
    base = os.path.join(ROOT, lang)
    print(f"[{lang}]{' (speech recognition only)' if stt_only else ''}")
    if lang == "en":
        fetch(f"{STT_REPO}/en/model.int8.onnx", os.path.join(base, "stt", "model.int8.onnx"))
        fetch(f"{STT_REPO}/en/tokens.txt", os.path.join(base, "stt", "tokens.txt"))
    else:
        fetch(f"{STT_REPO}/{lang}/model.int8.onnx", os.path.join(base, "stt", "model.int8.onnx"))
        fetch(f"{STT_REPO}/tokens.txt", os.path.join(base, "stt", "tokens.txt"))
    if stt_only:
        return
    fetch(f"{TTS_REPO}/{mms}/model.onnx", os.path.join(base, "tts", "model.onnx"))
    fetch(f"{TTS_REPO}/{mms}/tokens.txt", os.path.join(base, "tts", "tokens.txt"))
    with open(os.path.join(base, "pack.json"), "w", encoding="utf-8") as f:
        json.dump({"lang": lang, "stt": "nemo_ctc", "tts": "vits_mms", "mms": mms}, f)


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")] or ["hi", "ta", "en"]
    if "mt" in args:
        download_mt()
        args = [a for a in args if a != "mt"]
        if not args:
            return
    langs = list(LANGS) if args == ["all"] else args
    unknown = [l for l in langs if l not in LANGS]
    if unknown:
        sys.exit(f"Unknown language(s): {unknown}. Choose from {list(LANGS)} or 'all'.")
    fetch(VAD_URL, os.path.join(ROOT, "common", "silero_vad.onnx"))
    stt_only = "--stt-only" in sys.argv
    for lang in langs:
        download(lang, stt_only)
    print("Done. Copy to a phone with INSTALL_LANGUAGE_PACKS.bat")


if __name__ == "__main__":
    main()
