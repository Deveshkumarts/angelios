"""
Quantize the MMS-TTS (VITS) voices in models/packs/<lang>/tts to int8 with ONNX Runtime dynamic
quantization, and A/B them against the fp32 voice with the TTS -> STT round trip.

  python tools/quantize_tts.py --lite hi ta       # replace those voices with int8 "lite" voices
  python tools/quantize_tts.py --compare hi ta en # print size / speed / WER for fp32 vs int8

Measured (laptop CPU, 5 sentences): int8 MatMul+Conv is 38 MB instead of 114 MB but synthesises
~3.5x slower (RTF ~1.0 vs ~0.3); MatMul-only saves nothing. So the full-precision voice stays the
default; use --lite only for phones that are short on storage. The app loads model.onnx when
present, otherwise model.int8.onnx.
Requires: pip install onnxruntime onnx sherpa-onnx numpy
"""
import os
import sys
import time

import numpy as np

PACKS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "models", "packs")
OPS = {"matmul": ["MatMul"], "matmul+conv": ["MatMul", "Conv"]}


def quantize(lang: str, ops: str = "matmul+conv", out_name: str = "model.int8.onnx") -> str:
    from onnxruntime.quantization import QuantType, quantize_dynamic
    src = os.path.join(PACKS, lang, "tts", "model.onnx")
    dst = os.path.join(PACKS, lang, "tts", out_name)
    quantize_dynamic(src, dst, op_types_to_quantize=OPS[ops], weight_type=QuantType.QUInt8)
    return dst


def compare(lang: str, variants) -> None:
    import sherpa_onnx
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from eval_speech_roundtrip import SENTENCES, norm, resample, wer
    base = os.path.join(PACKS, lang)
    asr = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=os.path.join(base, "stt", "model.int8.onnx"), tokens=os.path.join(base, "stt", "tokens.txt"),
        num_threads=2, decoding_method="greedy_search")
    for label, path in variants:
        tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=path, tokens=os.path.join(base, "tts", "tokens.txt")),
            num_threads=2)))
        errs = words = 0.0
        synth = audio = 0.0
        for ref in SENTENCES[lang]:
            t0 = time.time()
            a = tts.generate(ref, sid=0, speed=1.0)
            synth += time.time() - t0
            x = resample(np.array(a.samples, dtype=np.float32), a.sample_rate, 16000)
            audio += len(x) / 16000
            st = asr.create_stream()
            st.accept_waveform(16000, x)
            asr.decode_stream(st)
            n = len(norm(ref).split())
            errs += wer(norm(ref), norm(st.result.text)) * n
            words += n
        print(f"  [{lang}] {label:12s} {os.path.getsize(path) / 1e6:6.1f} MB  RTF {synth / audio:.2f}  "
              f"round-trip WER {errs / words:.1%}")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = sys.argv[1:]
    do_compare = "--compare" in args
    langs = [a for a in args if not a.startswith("--")] or ["hi", "ta", "en"]
    for lang in langs:
        if do_compare:
            a = quantize(lang, "matmul", "model.int8-matmul.onnx")
            b = quantize(lang, "matmul+conv", "model.int8.onnx")
            compare(lang, [("fp32", os.path.join(PACKS, lang, "tts", "model.onnx")),
                           ("int8 matmul", a), ("int8 mm+conv", b)])
            os.remove(a)
            os.remove(b)  # --compare only measures; --lite installs
        elif "--lite" in args:
            out = quantize(lang)
            os.remove(os.path.join(PACKS, lang, "tts", "model.onnx"))
            print(lang, "-> lite voice", out)
        else:
            print("Nothing to do: pass --lite or --compare")
