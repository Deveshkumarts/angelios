"""
Desktop sanity check / benchmark for the on-device speech models (same files the phone uses).

For each language: MMS-TTS speaks the test sentences, IndicConformer transcribes that audio, and we
report word error rate (WER), TTS real-time factor (RTF) and STT RTF. Synthetic speech is cleaner
than a real microphone, so treat the WER as a lower bound; record real speakers for the final number.

Usage:  python tools/eval_speech_roundtrip.py hi ta en
Requires: pip install sherpa-onnx==2.0.1 numpy
"""
import os
import sys
import time

import numpy as np
import sherpa_onnx

PACKS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "models", "packs")

SENTENCES = {
    "en": ["we need food and water", "the road is blocked do not come this way",
           "medical team needed near the bridge", "we are safe", "where are you"],
    "hi": ["हमें भोजन और पानी चाहिए", "सड़क बंद है इस तरफ मत आओ",
           "पुल के पास चिकित्सा दल की जरूरत है", "हम सुरक्षित हैं", "आप कहाँ हैं"],
    "ta": ["எங்களுக்கு உணவும் தண்ணீரும் தேவை", "சாலை மூடப்பட்டுள்ளது இந்த வழியில் வர வேண்டாம்",
           "பாலத்தின் அருகே மருத்துவக் குழு தேவை", "நாங்கள் பாதுகாப்பாக இருக்கிறோம்", "நீங்கள் எங்கே இருக்கிறீர்கள்"],
}


def wer(ref: str, hyp: str) -> float:
    r, h = ref.split(), hyp.split()
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)] / max(1, len(r))


def norm(s: str) -> str:
    keep = "".join(c if c.isalnum() or c.isspace() else " " for c in s.lower())
    return " ".join(keep.split())


def resample(x: np.ndarray, src: int, dst: int) -> np.ndarray:
    if src == dst:
        return x
    n = int(len(x) * dst / src)
    return np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)


def run(lang: str) -> None:
    base = os.path.join(PACKS, lang)
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=os.path.join(base, "tts", "model.onnx"),
                tokens=os.path.join(base, "tts", "tokens.txt")),
            num_threads=2)))
    asr = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=os.path.join(base, "stt", "model.int8.onnx"),
        tokens=os.path.join(base, "stt", "tokens.txt"),
        num_threads=2, decoding_method="greedy_search")

    errs, words, tts_t, tts_a, stt_t = 0.0, 0, 0.0, 0.0, 0.0
    print(f"\n[{lang}]")
    for ref in SENTENCES[lang]:
        t0 = time.time()
        audio = tts.generate(ref, sid=0, speed=1.0)
        t1 = time.time()
        samples = resample(np.array(audio.samples, dtype=np.float32), audio.sample_rate, 16000)
        dur = len(samples) / 16000
        st = asr.create_stream()
        st.accept_waveform(16000, samples)
        t2 = time.time()
        asr.decode_stream(st)
        t3 = time.time()
        hyp = st.result.text
        w = wer(norm(ref), norm(hyp))
        n = len(norm(ref).split())
        errs += w * n
        words += n
        tts_t += t1 - t0
        tts_a += dur
        stt_t += t3 - t2
        print(f"  ref: {ref}\n  hyp: {hyp}\n  WER {w:.0%}  audio {dur:.1f}s")
    print(f"  => WER {errs / max(1, words):.1%} | TTS RTF {tts_t / tts_a:.2f} | STT RTF {stt_t / tts_a:.3f}")


if __name__ == "__main__":
    for l in sys.argv[1:] or ["hi", "ta", "en"]:
        run(l)
