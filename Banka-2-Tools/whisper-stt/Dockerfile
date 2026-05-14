# syntax=docker/dockerfile:1.7
#
# Whisper STT sidecar — Phase 5 voice input za Arbitro asistenta.
# Reference: github.com/SYSTRAN/faster-whisper (CTranslate2 backend, MIT license,
# ~4x brzi od openai-whisper uz polovinu VRAM-a).
#
# Multi-stage build:
#   Stage 1 (builder) — pip install faster-whisper u /opt/venv, pre-bake "tiny"
#                       multilingual model (~39MB FP16 / ~75MB int8) tako da
#                       prvi /transcribe poziv ne ide u network (bez 30s
#                       download-a iz hf hub-a).
#   Stage 2 (runtime) — kopira /opt/venv + ffmpeg + libsndfile1, BEZ
#                       build-essential (smanjuje image ~250MB).
#
# Mirror fallback strategija (GHA Azure runner debugging 04.05.2026 BE Dockerfile):
#   1. Probamo default mirror (archive.ubuntu.com:80)
#   2. Fallback na azure.archive.ubuntu.com — INTERNI Azure datacenter mirror
#   3. Last-resort fallback na HTTPS varijantu archive.ubuntu.com:443
# Svaki pokusaj ima Acquire::Retries=5 (interno apt retry) + 3-step bash retry
# sa exponential backoff (10s, 20s, 30s).

# ---------------------------------------------------------------------------
# Stage 1: builder — pip install + model pre-bake
# ---------------------------------------------------------------------------
FROM python:3.11-slim AS builder

# build-essential treba u slucaju da pip dep zahteva native compile;
# u runtime stage-u nece biti potreban (samo pre-built wheels iz venv-a).
RUN set -eux; \
    install_pkgs() { \
        for i in 1 2 3; do \
            if apt-get -o Acquire::Retries=5 update \
               && apt-get -o Acquire::Retries=5 install -y --no-install-recommends \
                    build-essential ca-certificates curl; then \
                return 0; \
            fi; \
            [ "$i" = "3" ] && return 1; \
            echo "apt-get attempt $i failed, retrying in $((i * 10))s..."; \
            sleep $((i * 10)); \
        done; \
    }; \
    switch_mirror() { \
        from="$1"; to="$2"; \
        if [ -f /etc/apt/sources.list.d/debian.sources ]; then \
            sed -i "s|${from}|${to}|g" /etc/apt/sources.list.d/debian.sources; \
        fi; \
        if [ -f /etc/apt/sources.list ]; then \
            sed -i "s|${from}|${to}|g" /etc/apt/sources.list; \
        fi; \
    }; \
    if ! install_pkgs; then \
        echo "Default Debian mirror failed — switching to cloudfront mirror"; \
        switch_mirror 'http://deb.debian.org' 'http://cloudfront.debian.net'; \
        if ! install_pkgs; then \
            echo "Cloudfront mirror failed — switching to HTTPS upstream"; \
            switch_mirror 'http://cloudfront.debian.net' 'https://deb.debian.org'; \
            install_pkgs || { echo "All mirrors failed" >&2; exit 1; }; \
        fi; \
    fi; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Izolovan virtualenv u /opt/venv tako da Stage 2 moze copy --from=builder
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

COPY requirements.txt .

# BuildKit cache mount za pip — keshira download-ovane wheel-ove izmedju
# no-cache rebuild-ova (sve dok requirements.txt nije menjan).
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

# Pre-bake Whisper "tiny" multilingual model u /app/model_cache.
# Bez ovog koraka, prvi /transcribe poziv hangs ~30s dok faster-whisper
# download-uje model iz HuggingFace hub-a.
# compute_type=int8 -> najmanji file (~39MB) i najbrza CPU inferencija.
RUN mkdir -p /app/model_cache && \
    python -c "from faster_whisper import WhisperModel; \
WhisperModel('tiny', device='cpu', compute_type='int8', download_root='/app/model_cache')"

# ---------------------------------------------------------------------------
# Stage 2: runtime — slim image, ffmpeg + libsndfile za audio dekodiranje
# ---------------------------------------------------------------------------
FROM python:3.11-slim

# ffmpeg za audio dekodiranje (webm/opus/wav/mp3/mp4 iz browser MediaRecorder-a).
# faster-whisper preko CTranslate2 interno koristi av (PyAV) ili ffmpeg subprocess.
# libsndfile1 je za eventualni soundfile fallback path.
# curl ostaje samo za docker-compose healthcheck-ove.
RUN set -eux; \
    install_pkgs() { \
        for i in 1 2 3; do \
            if apt-get -o Acquire::Retries=5 update \
               && apt-get -o Acquire::Retries=5 install -y --no-install-recommends \
                    ffmpeg libsndfile1 curl; then \
                return 0; \
            fi; \
            [ "$i" = "3" ] && return 1; \
            echo "apt-get attempt $i failed, retrying in $((i * 10))s..."; \
            sleep $((i * 10)); \
        done; \
    }; \
    switch_mirror() { \
        from="$1"; to="$2"; \
        if [ -f /etc/apt/sources.list.d/debian.sources ]; then \
            sed -i "s|${from}|${to}|g" /etc/apt/sources.list.d/debian.sources; \
        fi; \
        if [ -f /etc/apt/sources.list ]; then \
            sed -i "s|${from}|${to}|g" /etc/apt/sources.list; \
        fi; \
    }; \
    if ! install_pkgs; then \
        echo "Default Debian mirror failed — switching to cloudfront mirror"; \
        switch_mirror 'http://deb.debian.org' 'http://cloudfront.debian.net'; \
        if ! install_pkgs; then \
            echo "Cloudfront mirror failed — switching to HTTPS upstream"; \
            switch_mirror 'http://cloudfront.debian.net' 'https://deb.debian.org'; \
            install_pkgs || { echo "All mirrors failed" >&2; exit 1; }; \
        fi; \
    fi; \
    rm -rf /var/lib/apt/lists/*

# Kopiraj venv + pre-baked model iz builder stage-a
COPY --from=builder /opt/venv /opt/venv
COPY --from=builder /app/model_cache /app/model_cache

ENV PATH="/opt/venv/bin:$PATH"
# Ne treba HF_HOME jer faster-whisper koristi download_root koji eksplicitno
# prosledjujemo iz main.py-a.
ENV PYTHONUNBUFFERED=1

WORKDIR /app

COPY app/ ./app/

EXPOSE 8093

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD curl -f http://localhost:8093/health || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8093"]
