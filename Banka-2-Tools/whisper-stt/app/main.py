"""
Whisper STT sidecar — Phase 5 voice input za Arbitro asistenta.

Hostuje faster-whisper "tiny" multilingual model (~39MB FP16 / ~75MB int8,
~32 jezika ukljucujuci srpski + engleski). Korisnik moze da mesa SR + EN u
istoj recenici (npr. "kupi 5 AAPL akcija") — "tiny" je dovoljan za nas
agentic command use case (kratke recenice, jasna namera).

Endpointi:
  POST /transcribe  — multipart audio in, JSON sa text + jezik + segmenti
  GET  /health      — status + ucitan model + compute_type

Anti-hallucination dizajn (3-slojna odbrana):
  Layer 1: pre-flight RMS check — ako je audio < 0.005 RMS (~praktically
           tisina), ne saljemo Whisper-u uopste, vracamo no_speech_detected.
  Layer 2: faster-whisper VAD filter (Silero VAD, threshold 0.5) + standardni
           Whisper quality thresholds (no_speech_threshold=0.6,
           compression_ratio_threshold=2.4, log_prob_threshold=-1.0).
  Layer 3: pattern filter za poznate Whisper-tiny halucinacije ("Thanks for
           watching", "Hvala", " ", "Subtitles by", ...) — ako prepoznamo i
           audio je < 3s, vracamo no_speech_detected sa reason="whisper_hallucination_pattern".

Reference:
  - https://github.com/SYSTRAN/faster-whisper (CTranslate2 backend, MIT license)
  - https://github.com/snakers4/silero-vad (Silero VAD, MIT license)

Performance (CPU, int8, "tiny"):
  - 10s audio -> ~2-5s inferencija
  - VRAM: 0 (CPU-only deploy, max portability — radi i bez NVIDIA driver-a)
  - Memory footprint: ~250MB rezident u runtime container-u
"""

from __future__ import annotations

import logging
import os
import tempfile
import time
from typing import Optional

import numpy as np
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

# faster-whisper expose-uje decode_audio kao tanak ffmpeg wrapper koji
# vraca f32 mono PCM na zadatom sample rate-u. Koristimo ga za pre-flight
# RMS check (Layer 1) PRE nego sto saljemo audio kroz Whisper.
from faster_whisper import WhisperModel, decode_audio

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger("whisper-stt")

# ---------------------------------------------------------------------------
# Konfiguracija (env overridable za buducu fleksibilnost)
# ---------------------------------------------------------------------------
MODEL_NAME = os.getenv("WHISPER_MODEL", "tiny")
COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "int8")
DEVICE = os.getenv("WHISPER_DEVICE", "cpu")
MODEL_CACHE_DIR = os.getenv("WHISPER_CACHE_DIR", "/app/model_cache")

# Maks upload — 25 MB pokriva ~25 min mono 16kHz WAV-a, vise nego dovoljno
# za jednu agentic komandu (tipicno <10s).
MAX_UPLOAD_BYTES = 25 * 1024 * 1024

# Layer 1: RMS threshold za pre-flight tisina detekciju.
# Pravi govor ima RMS u opsegu 0.01-0.3. Tisina/ambient noise < 0.005.
RMS_SILENCE_THRESHOLD = 0.005

# Layer 3: poznate Whisper-tiny halucinacije na tisini/muzici (zato sto je
# treniran na YouTube transkriptcijama). Filter samo na kratkim audio-ima
# (< 3s) — duzi audio sa istom transkripcijom moze biti legit korisnik.
HALLUCINATION_PATTERNS = {
    "thanks for watching",
    "thanks for watching!",
    "thanks for watching.",
    "thank you.",
    "thank you",
    "thanks.",
    "thanks",
    "you",
    "subtitles by",
    "subtitles by the amara.org community",
    "subtitled by the amara.org community",
    "hvala",
    "hvala vam",
    "hvala vam.",
    "hvala na gledanju",
    "hvala na gledanju.",
    "[music]",
    "[muzika]",
    "...",
    "",
    ".",
}

# Whisper interno radi na 16kHz mono — decode_audio nam vraca PCM na ovom rate-u.
TARGET_SAMPLE_RATE = 16000

# ---------------------------------------------------------------------------
# Globalni model singleton (lazy-loaded u startup-u)
# ---------------------------------------------------------------------------
WHISPER_MODEL: Optional[WhisperModel] = None


def get_model() -> WhisperModel:
    """Lazy-load + cache globalni WhisperModel singleton."""
    global WHISPER_MODEL
    if WHISPER_MODEL is None:
        t0 = time.time()
        log.info(
            "Loading Whisper model name=%s device=%s compute_type=%s cache=%s",
            MODEL_NAME, DEVICE, COMPUTE_TYPE, MODEL_CACHE_DIR,
        )
        WHISPER_MODEL = WhisperModel(
            MODEL_NAME,
            device=DEVICE,
            compute_type=COMPUTE_TYPE,
            download_root=MODEL_CACHE_DIR,
        )
        log.info("Whisper model loaded in %.2fs", time.time() - t0)
    return WHISPER_MODEL


# ---------------------------------------------------------------------------
# Response shema (Pydantic za auto OpenAPI dokumentaciju)
# ---------------------------------------------------------------------------
class SegmentDto(BaseModel):
    start: float
    end: float
    text: str


class TranscribeResponse(BaseModel):
    """Response shema poravnata sa BE WhisperTranscription DTO ugovorom.

    Polje `detected_speech` je pozitivan flag — `True` znaci da je transkripcija
    validan govor. `False` znaci da nista nije pronadjeno (RMS prag / VAD prazan /
    halucinacija). BE u tom slucaju vraca user-friendly 400 "Nisam te cuo".
    """
    text: str
    language: Optional[str] = None
    language_probability: Optional[float] = None
    duration_seconds: float = 0.0
    speech_duration_seconds: float = 0.0
    voice_activity_ratio: float = 0.0
    detected_speech: bool = False
    reason: Optional[str] = None
    segments: list[SegmentDto] = []


# ---------------------------------------------------------------------------
# FastAPI app + CORS
# ---------------------------------------------------------------------------
app = FastAPI(title="Banka 2 Whisper STT", version="1.0.0")

# CORS: BE Spring poziva ovaj sidecar interno preko Docker mreze (sidecar:port
# alias `whisper-stt:8093`). Lokalni dev sa BE na 8080 na host-u takodje treba
# pristup. FE NIKAD ne zove ovaj sidecar direktno — sve audio prolazi kroz
# BE /assistant/voice/transcribe wrapper koji proxy-uje multipart upload.
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://banka2_backend:8080",
        "http://localhost:8080",
        "http://127.0.0.1:8080",
    ],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)


@app.on_event("startup")
def startup():
    log.info(
        "Whisper STT sidecar startup model=%s device=%s compute_type=%s",
        MODEL_NAME, DEVICE, COMPUTE_TYPE,
    )
    # Eager load tako da prvi /transcribe poziv ne ceka cold start.
    # Model je pre-baked u /app/model_cache tokom Docker build-a, pa load
    # samo otvara fajlove sa diska (~1-2s na slim CPU-u).
    try:
        get_model()
    except Exception as e:
        log.exception("Failed to eager-load Whisper model: %s", e)
        # Ne raise-uj — /health ce vratiti model_loaded=false, klijenti mogu retry


@app.get("/health")
def health():
    """Health check + model status (za docker-compose healthcheck i BE smoke)."""
    return {
        "status": "healthy",
        "service": "whisper-stt",
        "version": "1.0.0",
        "model": MODEL_NAME,
        "device": DEVICE,
        "compute_type": COMPUTE_TYPE,
        "model_loaded": WHISPER_MODEL is not None,
        "max_upload_mb": MAX_UPLOAD_BYTES // (1024 * 1024),
    }


# ---------------------------------------------------------------------------
# Anti-hallucination helperi
# ---------------------------------------------------------------------------
def _compute_rms(audio: np.ndarray) -> float:
    """Root mean square = mera srednje glasnoce signala u [-1, 1] f32 array-u."""
    if audio.size == 0:
        return 0.0
    # f64 akumulacija sprecava overflow na dugim audio-ima
    return float(np.sqrt(np.mean(np.square(audio, dtype=np.float64))))


def _is_hallucination(text: str, duration: float) -> bool:
    """Layer 3: pattern-match poznate Whisper-tiny halucinacije."""
    if duration >= 3.0:
        # Audio je dovoljno dug da bi imitirao legit kratku recenicu;
        # ne brisemo cak i ako matchuje pattern.
        return False
    normalized = text.strip().lower()
    if not normalized:
        return True
    # Strip terminalne tacke/uzvicnike za matching
    stripped = normalized.rstrip(".!?…").strip()
    return stripped in HALLUCINATION_PATTERNS or normalized in HALLUCINATION_PATTERNS


# ---------------------------------------------------------------------------
# Glavni /transcribe endpoint
# ---------------------------------------------------------------------------
@app.post("/transcribe", response_model=TranscribeResponse)
async def transcribe(
    audio: UploadFile = File(..., description="Audio fajl (webm/ogg/wav/mp3/mp4)"),
    language: Optional[str] = Form(default=None, description="ISO 639-1 jezik kod; prazno = autodetect"),
    vad_threshold: float = Form(default=0.5, description="Silero VAD threshold [0, 1]"),
):
    """
    Transkribuje audio fajl. ffmpeg interno dekoduje webm/opus/wav/mp3/mp4/m4a/flac.

    Form fields:
      - audio (required): audio upload (multipart/form-data, field name "audio" — paritet sa BE WhisperSttClient)
      - language (optional, default None): ISO 639-1 (npr. "sr", "en"); None = autodetect
      - vad_threshold (optional, default 0.5): Silero VAD speech detection prag [0, 1]

    Vraca TranscribeResponse JSON (vidi shemu). Polje `no_speech_detected=true`
    se vraca uvek kad jedan od 3 anti-hallucination layer-a odbije input;
    polje `reason` daje sloj koji je odbio:
      - "rms_below_threshold"          — Layer 1 (pre-flight RMS)
      - "vad_empty"                    — Layer 2 (VAD prosao, tekst prazan)
      - "whisper_hallucination_pattern" — Layer 3 (poznat pattern)
    """
    t0 = time.time()

    # ---- Read upload ----
    audio_bytes = await audio.read()
    if not audio_bytes:
        raise HTTPException(status_code=400, detail="Prazan audio upload.")
    if len(audio_bytes) > MAX_UPLOAD_BYTES:
        raise HTTPException(
            status_code=413,
            detail=(
                f"Audio fajl prevelik ({len(audio_bytes)} B). "
                f"Maksimum {MAX_UPLOAD_BYTES // (1024 * 1024)} MB."
            ),
        )

    # Validacija VAD threshold opsega
    if not (0.0 <= vad_threshold <= 1.0):
        raise HTTPException(
            status_code=400,
            detail=f"vad_threshold mora biti u opsegu [0, 1], dobijeno: {vad_threshold}",
        )

    # Pisanje upload-a u temp fajl. faster-whisper preferira disk path
    # (interno spawn-uje ffmpeg subprocess za decoding). NamedTemporaryFile
    # sa delete=False da bismo mogli da ga zatvorimo i ponovo otvorimo
    # na Windows-u (gde delete-on-close = exclusive lock). Cleanup u finally.
    suffix = os.path.splitext(audio.filename or "")[1] or ".bin"
    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=suffix)
    try:
        tmp.write(audio_bytes)
        tmp.flush()
        tmp.close()

        # ----- Layer 1: pre-flight RMS check -----
        # Dekoduj audio na 16kHz mono f32 array i izmeri glasnocu. Ako je
        # ispod praga, vrati no_speech bez ulaska u Whisper inference.
        try:
            audio_array = decode_audio(tmp.name, sampling_rate=TARGET_SAMPLE_RATE)
        except Exception as e:
            log.exception("Audio decode failed: %s", e)
            raise HTTPException(
                status_code=400,
                detail=f"Audio nije moguce dekodirati (nepodrzan format ili corrupted): {e}",
            )

        # decode_audio moze vratiti torch.Tensor ili np.ndarray u zavisnosti
        # od faster-whisper verzije; normalizuj na np.ndarray za RMS racun.
        if not isinstance(audio_array, np.ndarray):
            try:
                audio_array = audio_array.numpy()  # torch.Tensor.numpy()
            except AttributeError:
                audio_array = np.asarray(audio_array)

        rms = _compute_rms(audio_array)
        audio_seconds = (
            float(len(audio_array) / TARGET_SAMPLE_RATE)
            if len(audio_array)
            else 0.0
        )

        if rms < RMS_SILENCE_THRESHOLD:
            elapsed = time.time() - t0
            log.info(
                "TRANSCRIBE no-speech (rms_below_threshold) rms=%.4f duration=%.2fs elapsed=%.2fs",
                rms, audio_seconds, elapsed,
            )
            return TranscribeResponse(
                text="",
                language=None,
                language_probability=None,
                duration_seconds=audio_seconds,
                speech_duration_seconds=0.0,
                voice_activity_ratio=0.0,
                detected_speech=False,
                reason="rms_below_threshold",
                segments=[],
            )

        # ----- Layer 2: faster-whisper VAD + quality thresholds -----
        model = get_model()
        try:
            segments_iter, info = model.transcribe(
                tmp.name,
                language=(language.strip() if language else None) or None,
                vad_filter=True,
                vad_parameters={
                    "threshold": vad_threshold,
                    "min_silence_duration_ms": 500,
                    "speech_pad_ms": 400,
                },
                no_speech_threshold=0.6,
                compression_ratio_threshold=2.4,
                log_prob_threshold=-1.0,
                condition_on_previous_text=False,
                beam_size=1,  # single beam = brze, dovoljno tacno za "tiny"
            )
        except Exception as e:
            # Loguj puni stack trace ali vrati generican message klijentu
            log.exception("Whisper transcribe call failed")
            raise HTTPException(
                status_code=500,
                detail="Greska tokom transkripcije.",
            ) from e

        # Materializuj segmente — segments_iter je lazy generator; tek
        # iteracija pokrece pravi inference rad u CTranslate2.
        segments: list[SegmentDto] = []
        text_parts: list[str] = []
        speech_secs = 0.0
        for seg in segments_iter:
            cleaned = (seg.text or "").strip()
            if cleaned:
                text_parts.append(cleaned)
            segments.append(SegmentDto(
                start=float(seg.start),
                end=float(seg.end),
                text=cleaned,
            ))
            speech_secs += max(0.0, float(seg.end) - float(seg.start))

        text = " ".join(text_parts).strip()
        duration = float(getattr(info, "duration", audio_seconds) or audio_seconds)
        detected_lang = getattr(info, "language", None)
        lang_prob = getattr(info, "language_probability", None)
        if lang_prob is not None:
            lang_prob = float(lang_prob)
        voice_activity = speech_secs / duration if duration > 0 else 0.0

        elapsed = time.time() - t0

        # Layer 2 (cont): VAD nije nasao govor → tekst je prazan
        if duration > 0 and not text:
            log.info(
                "TRANSCRIBE no-speech (vad_empty) duration=%.2fs lang=%s prob=%.2f rms=%.4f elapsed=%.2fs",
                duration, detected_lang, (lang_prob or 0.0), rms, elapsed,
            )
            return TranscribeResponse(
                text="",
                language=detected_lang,
                language_probability=lang_prob,
                duration_seconds=duration,
                speech_duration_seconds=speech_secs,
                voice_activity_ratio=voice_activity,
                detected_speech=False,
                reason="vad_empty",
                segments=segments,
            )

        # ----- Layer 3: poznate Whisper-tiny halucinacije -----
        if _is_hallucination(text, duration):
            log.info(
                "TRANSCRIBE no-speech (whisper_hallucination_pattern) "
                "duration=%.2fs lang=%s prob=%.2f rms=%.4f text=%r elapsed=%.2fs",
                duration, detected_lang, (lang_prob or 0.0), rms, text, elapsed,
            )
            return TranscribeResponse(
                text="",
                language=detected_lang,
                language_probability=lang_prob,
                duration_seconds=duration,
                speech_duration_seconds=speech_secs,
                voice_activity_ratio=voice_activity,
                detected_speech=False,
                reason="whisper_hallucination_pattern",
                segments=segments,
            )

        # ----- Sve cisto: vrati transkript -----
        log.info(
            "TRANSCRIBE OK duration=%.1fs lang=%s prob=%.2f text_len=%d rms=%.4f speech=%.2fs vad=%.2f elapsed=%.2fs",
            duration, detected_lang, (lang_prob or 0.0), len(text), rms, speech_secs, voice_activity, elapsed,
        )
        return TranscribeResponse(
            text=text,
            language=detected_lang,
            language_probability=lang_prob,
            duration_seconds=duration,
            speech_duration_seconds=speech_secs,
            voice_activity_ratio=voice_activity,
            detected_speech=True,
            reason=None,
            segments=segments,
        )
    finally:
        # Cleanup temp fajla bez obzira na ishod
        try:
            os.unlink(tmp.name)
        except OSError:
            pass
