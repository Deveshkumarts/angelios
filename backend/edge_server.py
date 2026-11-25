"""
===============================================================================
iTANTRA - Edge Mesh AI Server (FastAPI + PyTorch + AI4Bharat)
===============================================================================
Exposes REST and WebSocket endpoints for:
1. /api/v1/translate_speech : Full S2S Translation (Audio In -> Translated Audio Out)
2. /api/v1/stt              : IndicConformer Speech-to-Text
3. /api/v1/translate_text   : IndicTrans2 Machine Translation
4. /api/v1/tts              : IndicF5 Text-to-Speech
5. /api/v1/health           : GPU Telemetry (NVIDIA RTX 4050), VRAM & Link Status

Endpoints used by the Android app (OfflineTranslatorEngine.kt):
  GET  /api/status            : liveness probe  -> {"status", "device", "cuda_available"}
  POST /api/translate         : JSON {"text","src_lang","tgt_lang"} (FLORES or ISO codes)
                                -> {"translation": "..."}; 503 if no model can serve it
  GET  /api/pack/{flores_tag} : offline phrase pack JSON for a language (e.g. tam_Taml)

Run:  python edge_server.py   (listens on 0.0.0.0:5000, override with ITANTRA_PORT)
===============================================================================
"""

import os
import io
import time
from typing import Optional
import torch
try:
    import torchaudio
except ImportError:
    torchaudio = None
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import Response, JSONResponse, FileResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from pipeline import IndicSpeechToSpeechPipeline, DEVICE, LANGUAGE_ISO_MAP, normalize_lang

SERVER_PORT = int(os.environ.get("ITANTRA_PORT", "5000"))
# Phrase packs bundled with the app; the server hands the same files to phones that lack them.
PACKS_DIR = os.environ.get(
    "ITANTRA_PACKS_DIR",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "packs"),
)

app = FastAPI(
    title="iTANTRA Edge AI Speech Translation Server",
    version="2.0.0",
    description="Offline Tactical Speech-to-Speech Translation powered by AI4Bharat IndicConformer, IndicTrans2 & IndicF5"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Global Pipeline Instance
pipeline: IndicSpeechToSpeechPipeline = None

@app.on_event("startup")
def startup_event():
    global pipeline
    print("[*] Starting iTANTRA AI4Bharat Edge Server on", DEVICE)
    pipeline = IndicSpeechToSpeechPipeline()

@app.get("/api/v1/health")
def health_check():
    cuda_info = {}
    if torch.cuda.is_available():
        cuda_info = {
            "device_name": torch.cuda.get_device_name(0),
            "allocated_vram_mb": round(torch.cuda.memory_allocated(0) / (1024 * 1024), 2),
            "reserved_vram_mb": round(torch.cuda.memory_reserved(0) / (1024 * 1024), 2),
        }
    return {
        "status": "ONLINE",
        "mesh_node_role": "EDGE_AI_GATEWAY",
        "device": DEVICE,
        "cuda": cuda_info,
        "supported_models": {
            "vad": "Silero VAD (2M)",
            "stt": "AI4Bharat IndicConformer (600M / 120M)",
            "mt": "AI4Bharat IndicTrans2 (200M / 320M)",
            "tts": "AI4Bharat IndicF5 (400M)"
        }
    }

# ---------------------------------------------------------------- app-facing API

class TranslateRequest(BaseModel):
    text: str
    src_lang: str = "en"
    tgt_lang: str = "ta"
    tone: Optional[str] = None


@app.get("/api/status")
def app_status():
    return {
        "status": "online",
        "device": DEVICE,
        "cuda_available": torch.cuda.is_available(),
        "mt_loaded": bool(pipeline is not None and pipeline.mt.is_loaded),
        "languages": sorted(LANGUAGE_ISO_MAP.keys()),
    }


@app.post("/api/translate")
def app_translate(req: TranslateRequest):
    if pipeline is None:
        raise HTTPException(status_code=503, detail="Pipeline not initialized")
    src, tgt = normalize_lang(req.src_lang), normalize_lang(req.tgt_lang)
    if src not in LANGUAGE_ISO_MAP or tgt not in LANGUAGE_ISO_MAP:
        raise HTTPException(status_code=400, detail=f"Unsupported language pair {req.src_lang}->{req.tgt_lang}")
    start = time.time()
    translated = pipeline.mt.translate(req.text, src, tgt)
    if translated is None:
        raise HTTPException(status_code=503, detail=f"No translation model loaded for {src}->{tgt}")
    return {
        "translation": translated,
        "src_lang": src,
        "tgt_lang": tgt,
        "latency_ms": round((time.time() - start) * 1000, 1),
        "device": DEVICE,
    }


@app.get("/api/pack/{flores_tag}")
def app_language_pack(flores_tag: str):
    # Only plain FLORES tags like "tam_Taml" - never a path
    if not flores_tag.replace("_", "").isalnum():
        raise HTTPException(status_code=400, detail="Invalid language tag")
    path = os.path.join(PACKS_DIR, f"{flores_tag}.json")
    if not os.path.isfile(path):
        raise HTTPException(status_code=404, detail=f"No pack for {flores_tag}")
    return FileResponse(path, media_type="application/json")


# On-device speech model packs (STT: IndicConformer, TTS: MMS) downloaded by
# tools/download_language_packs.py. Phones pull them over the local Wi-Fi/hotspot, never the internet.
SPEECH_PACKS_DIR = os.path.realpath(os.environ.get(
    "ITANTRA_SPEECH_PACKS_DIR",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "models", "packs"),
))
_SPEECH_PACK_FILES = {"stt/model.int8.onnx", "stt/tokens.txt", "tts/model.onnx", "tts/tokens.txt"}
_MT_PACK_FILES = {
    "en-indic/encoder.onnx", "en-indic/decoder.onnx", "en-indic/vocab.json",
    "indic-en/encoder_model.onnx", "indic-en/decoder_model.onnx", "indic-en/decoder_with_past_model.onnx",
    "indic-en/src_vocab.txt", "indic-en/src_merges.txt", "indic-en/tgt_vocab.txt", "indic-en/it2_meta.json",
}


@app.get("/api/speechpack/{lang}/{kind}/{name}")
def speech_pack_file(lang: str, kind: str, name: str):
    rel = f"{kind}/{name}"
    if lang == "common" and rel == "vad/silero_vad.onnx":
        pass  # shared Silero VAD, stored at packs/common/silero_vad.onnx
    elif lang == "mt" and rel in _MT_PACK_FILES:
        pass  # offline IndicTrans2 translation pack
    elif not (lang.isalpha() and len(lang) <= 3 and rel in _SPEECH_PACK_FILES):
        raise HTTPException(status_code=400, detail="Invalid speech pack path")
    path = os.path.realpath(os.path.join(SPEECH_PACKS_DIR, lang, kind, name) if lang != "common"
                            else os.path.join(SPEECH_PACKS_DIR, "common", "silero_vad.onnx"))
    if not path.startswith(SPEECH_PACKS_DIR + os.sep) or not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="Speech pack file not on this base station")
    return FileResponse(path, media_type="application/octet-stream")


# ---------------------------------------------------------------- v1 API

@app.post("/api/v1/translate_speech")
async def translate_speech_endpoint(
    audio_file: UploadFile = File(...),
    src_lang: str = Form("en"),
    tgt_lang: str = Form("ta")
):
    """
    Receives voice audio from Phone A (e.g. English), transcribes via IndicConformer,
    translates via IndicTrans2, and returns Phone B's translated voice (e.g. Tamil).
    """
    temp_in = f"temp_in_{int(time.time() * 1000)}.wav"
    temp_out = f"temp_out_{int(time.time() * 1000)}.wav"
    
    try:
        content = await audio_file.read()
        with open(temp_in, "wb") as f:
            f.write(content)
            
        result = pipeline.translate_speech(
            audio_input_path=temp_in,
            source_language=src_lang,
            target_language=tgt_lang,
            output_audio_path=temp_out
        )
        
        # Read output audio
        with open(temp_out, "rb") as f:
            out_bytes = f.read()
            
        return Response(
            content=out_bytes,
            media_type="audio/wav",
            headers={
                "X-Source-Lang": src_lang,
                "X-Target-Lang": tgt_lang,
                "X-Transcript": result.transcription,
                "X-Translated-Text": result.translated_text,
                "X-Total-Latency-Ms": str(round(result.timings_ms.get("total_ms", 0), 1))
            }
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if os.path.exists(temp_in):
            os.remove(temp_in)
        if os.path.exists(temp_out):
            os.remove(temp_out)

@app.post("/api/v1/translate_text")
def translate_text_endpoint(
    text: str = Form(...),
    src_lang: str = Form("en"),
    tgt_lang: str = Form("ta")
):
    """
    Translates text directly using AI4Bharat IndicTrans2 on NVIDIA RTX 4050 GPU.
    """
    if pipeline is None:
        raise HTTPException(status_code=503, detail="Pipeline not initialized")
    translated = pipeline.mt.translate(text, normalize_lang(src_lang), normalize_lang(tgt_lang))
    if translated is None:
        raise HTTPException(status_code=503, detail="No translation model loaded for this language pair")
    return {
        "source_text": text,
        "translated_text": translated,
        "src_lang": src_lang,
        "tgt_lang": tgt_lang,
        "device": DEVICE
    }

if __name__ == "__main__":
    import uvicorn
    print(f"[*] Launching iTANTRA Edge AI Server on port {SERVER_PORT}...")
    uvicorn.run(app, host="0.0.0.0", port=SERVER_PORT)
