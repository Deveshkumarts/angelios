# On-device STT on real speech (Google FLEURS test set)

Models: the exact `models/packs/<lang>/stt` files the phone runs (AI4Bharat IndicConformer CTC int8; English: NeMo FastConformer CTC int8), sherpa-onnx, greedy decoding, 2 CPU threads (laptop).
Data: first 50 clips of each FLEURS test split (read Wikipedia sentences, real speakers).
WER/CER after lower-casing and removing punctuation. CER is the fairer figure for agglutinative
languages (Tamil, Telugu, Kannada, Malayalam), where one word carries a whole phrase.

Numbers 0-99 written as digits in a reference are converted to words before scoring (the
recogniser writes words). Near-miss = share of word errors only 1-2 characters off
(a suffix or vowel sign) rather than a wrong word.

Transcripts get the app's TranscriptCleanup. "WER excl." sets aside clips whose reference
covers only part of the audio (hypothesis > 2x reference + 5 words; count in brackets).

| Language | Clips | Audio (min) | WER % | WER excl. % | CER % | Near-miss % of errors | RTF |
|---|---|---|---|---|---|---|---|
| Hindi | 50 | 9.8 | 11.0 | 11.0 (0) | 4.1 | 56.4 | 0.09 |
| Tamil | 50 | 11.2 | 29.7 | 29.7 (0) | 12.6 | 32.5 | 0.1 |
| Telugu | 50 | 9.2 | 21.2 | 21.2 (0) | 5.8 | 24.6 | 0.112 |
| Kannada | 50 | 12.1 | 15.7 | 15.7 (0) | 3.5 | 41.2 | 0.125 |
| Malayalam | 50 | 13.0 | 21.0 | 21.0 (0) | 5.0 | 34.3 | 0.109 |
| Bengali | 50 | 11.3 | 13.5 | 13.5 (0) | 3.6 | 44.4 | 0.093 |
| Marathi | 50 | 12.0 | 18.7 | 18.7 (0) | 4.5 | 43.1 | 0.097 |
| Gujarati | 50 | 9.2 | 20.1 | 20.1 (0) | 5.9 | 38.2 | 0.096 |
| Odia | 50 | 9.6 | 25.6 | 25.6 (0) | 6.8 | 36.5 | 0.098 |
| Punjabi | 50 | 9.9 | 14.4 | 14.4 (0) | 4.8 | 62.6 | 0.095 |
| English | 50 | 7.9 | 7.5 | 7.5 (0) | 4.0 | 32.9 | 0.033 |

Reproduce: `python tools/eval_wer_fleurs.py` (downloads ~20-40 MB of clips per language).
