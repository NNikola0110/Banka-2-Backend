# Whisper STT sidecar

Speech-to-text sidecar za Arbitro voice input. Hostuje **faster-whisper "tiny"
multilingual** model (~39 MB) na CPU-u sa int8 kvantizacijom. Pokriva ~32 jezika
ukljucujuci **srpski + engleski**, sto je presudno za nas use case gde korisnik
mesa jezike u istoj recenici (npr. `"kupi 5 AAPL akcija"`, `"prodaj milicinim
racunom 10 MSFT shares"`).

## Zasto faster-whisper + tiny + int8

- **faster-whisper** koristi CTranslate2 backend — **~4× brzi** od openai-whisper
  uz **polovinu VRAM-a**. Ima built-in Silero VAD od verzije 0.10+.
- **tiny** je najmanji Whisper model (39 MB). Vise je nego dovoljan za kratke
  agentic komande; `base`/`small` ne donose realnu prednost a triplaju image size.
- **int8** kvantizacija je optimalna za CPU inferenciju: ~2-5 s na 10 s audio.
- **CPU-only deploy** — max portability, ne zahteva NVIDIA driver na host-u.

## Port

`8093` (sledeci slobodan iza Kokoro TTS 8092).

## Endpointi

| Method | Path          | Description                                       |
|--------|---------------|---------------------------------------------------|
| `GET`  | `/health`     | Status + ucitan model + compute_type              |
| `POST` | `/transcribe` | Multipart audio in, JSON sa text + segmenti       |

### `POST /transcribe`

**Form fields:**

| Field           | Required | Default              | Opis                                                            |
|-----------------|----------|----------------------|-----------------------------------------------------------------|
| `file`          | yes      | —                    | Audio (webm/ogg/wav/mp3/mp4/m4a/flac — ffmpeg dekoduje sve)     |
| `language`      | no       | `""` (autodetect)    | ISO 639-1 (npr. `sr`, `en`)                                     |
| `vad_threshold` | no       | `0.5`                | Silero VAD speech detection prag `[0, 1]`                       |

**Response 200 (govor detektovan):**

```json
{
  "text": "kupi 5 AAPL akcija",
  "language": "sr",
  "language_probability": 0.94,
  "duration": 4.2,
  "no_speech_detected": false,
  "reason": null,
  "segments": [{"start": 0.0, "end": 4.0, "text": "kupi 5 AAPL akcija"}]
}
```

**Response 200 (govor nije detektovan / odbijen):**

```json
{
  "text": "",
  "language": null,
  "duration": 1.2,
  "no_speech_detected": true,
  "reason": "rms_below_threshold",
  "segments": []
}
```

Vrednosti `reason` (samo kad `no_speech_detected: true`):

- `rms_below_threshold` — Layer 1 (audio je previse tih, ne ulazi u model)
- `vad_empty` — Layer 2 (VAD prosao, model nije nasao govor)
- `whisper_hallucination_pattern` — Layer 3 (poznata halucinacija na kratkom audio-u)

**Greske:**

| Status | Razlog                                                                        |
|--------|-------------------------------------------------------------------------------|
| 400    | Prazan upload / nepodrzan/corrupted audio / `vad_threshold` van `[0, 1]`      |
| 413    | Audio veci od 25 MB                                                           |
| 500    | Internal Whisper greska (stack trace u log-u, klijent dobija generic message) |

### `GET /health`

```json
{
  "status": "healthy",
  "service": "whisper-stt",
  "version": "1.0.0",
  "model": "tiny",
  "device": "cpu",
  "compute_type": "int8",
  "model_loaded": true,
  "max_upload_mb": 25
}
```

## Anti-hallucination (3-slojna odbrana)

Whisper modeli (posebno `tiny`) na **tisini ili muzici** rado halucinaraju
YouTube-style fraze tipa _"Thanks for watching"_, _"Subtitles by..."_, _"Hvala
vam"_. Tri sloja sprecavaju da takve halucinacije procure ka korisniku:

1. **Pre-flight RMS check** — pre Whisper inference-a, dekodujemo audio na 16
   kHz mono kroz `faster_whisper.decode_audio` i merimo srednju glasnocu. Ako
   je RMS < 0.005 (~tisina/ambient noise), vracamo `no_speech_detected` bez
   pozivanja Whisper-a uopste.
2. **faster-whisper VAD filter** — `vad_filter=True` sa Silero VAD (default
   threshold 0.5, `min_silence_duration_ms=500`, `speech_pad_ms=400`). Plus
   standard Whisper quality gates: `no_speech_threshold=0.6`,
   `compression_ratio_threshold=2.4`, `log_prob_threshold=-1.0`,
   `condition_on_previous_text=False`, `beam_size=1`.
3. **Pattern filter** — ako transkript matchuje poznat halucinacijski string
   (`thanks for watching`, `hvala`, `hvala vam`, `subtitles by`, `[music]`,
   `[muzika]`, `you`, ` `, `...`) i audio je krazi od 3 sekunde, vracamo
   `no_speech_detected` sa `reason: "whisper_hallucination_pattern"`. Duzi
   audio sa istom transkripcijom NE triggeruje filter (mozda je legit kratak
   korisnikov input).

## Pre-baked model (no first-start download)

Dockerfile builder stage pre-download-uje Whisper "tiny" model u
`/app/model_cache` tokom `docker build`-a:

```dockerfile
RUN python -c "from faster_whisper import WhisperModel; \
WhisperModel('tiny', device='cpu', compute_type='int8', download_root='/app/model_cache')"
```

Runtime stage kopira `/app/model_cache` iz builder-a. **Posledica:** prvi
`POST /transcribe` poziv NIJE blokiran 30 s download-om sa HuggingFace hub-a.
`docker compose up -d --build` proizvodi odmah upotrebljiv sidecar.

## Konfiguracija (env vars)

| Var                    | Default            | Opis                                     |
|------------------------|--------------------|------------------------------------------|
| `WHISPER_MODEL`        | `tiny`             | Naziv modela (`tiny`/`base`/`small`/...) |
| `WHISPER_COMPUTE_TYPE` | `int8`             | `int8`/`float16`/`float32`               |
| `WHISPER_DEVICE`       | `cpu`              | `cpu` ili `cuda`                         |
| `WHISPER_CACHE_DIR`    | `/app/model_cache` | Direktorijum sa pre-baked modelom        |

## Docker mreza

- Container expose: **8093**
- Docker network alias: `whisper-stt:8093`
- BE poziva interno preko `http://whisper-stt:8093/transcribe`
- FE NIKAD ne zove sidecar direktno — sve audio prolazi kroz BE wrapper
  (`/assistant/voice/transcribe`) zbog auth + audit + rate limit-a. CORS dozvoljava
  samo BE origin-e (`banka2_backend:8080`, `localhost:8080`, `127.0.0.1:8080`).

## Primeri curl-a

```bash
# Snimi 5 s audio sa mikrofona (Linux):
ffmpeg -f alsa -i default -t 5 -ac 1 -ar 16000 sample.wav

# Transcript sa eksplicitnim jezikom:
curl -F "file=@sample.wav" -F "language=sr" http://localhost:8093/transcribe

# Autodetect jezika:
curl -F "file=@sample.wav" http://localhost:8093/transcribe

# Strogi VAD (filtrira sapat / pozadinski sum):
curl -F "file=@sample.wav" -F "vad_threshold=0.7" http://localhost:8093/transcribe

# Health check:
curl http://localhost:8093/health
```

## Performance (Intel i7, CPU-only, int8)

| Audio length | Inference time | RTF   |
|--------------|----------------|-------|
| 3 s          | ~1.0 s         | 0.33× |
| 10 s         | ~2.5 s         | 0.25× |
| 30 s         | ~7.5 s         | 0.25× |

Cold start: ~1-2 s za model load (sa diska, bez network-a).

## Build & run (standalone, bez compose)

```bash
docker build -t banka2-whisper-stt:latest .
docker run --rm -p 8093:8093 banka2-whisper-stt:latest
```

## License

- Whisper model weights: MIT (OpenAI)
- faster-whisper: MIT (SYSTRAN)
- Silero VAD: MIT (snakers4)
