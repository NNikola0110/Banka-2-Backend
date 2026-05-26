# 11-arbitro/ — Arbitro AI sidecari (OPCIONO)

Arbitro AI asistent je opciona Celina 6 (NIJE u Celina 1-5 spec-u). Ovaj folder
sadrzi K8s manifeste za 5 sidecara koji cine Arbitro stack:

| Komponenta | Image | Port | Tip | Resursi |
|------------|-------|------|-----|---------|
| ollama | `ghcr.io/raf-si-2025/banka-2-ollama:latest` | 11434 | LLM (Gemma 4 E2B) | **GPU obavezan** (8GB+ VRAM), ~5GB disk image |
| wikipedia-service | `ghcr.io/raf-si-2025/banka-2-wikipedia-tool:latest` | 8090 | Tool (Wiki search) | CPU only, 256Mi RAM |
| rag-service | `ghcr.io/raf-si-2025/banka-2-rag-tool:latest` | 8091 | Tool (ChromaDB RAG) | CPU only, 1Gi RAM, 5Gi PVC |
| kokoro-tts | `ghcr.io/raf-si-2025/banka-2-kokoro-tts:latest` | 8092 | TTS (Kokoro-82M) | CPU (0.3x RT) ili GPU (6x RT), 1Gi PVC za HF cache |
| whisper-stt | `ghcr.io/raf-si-2025/banka-2-whisper-stt:latest` | 8093 | STT (faster-whisper) | CPU only, 512Mi RAM |

## Kako preskociti ako nema GPU node

**Opcija A: preskoci ceo folder.** DevOps tim ne primjenjuje `11-arbitro/` —
banka-core graceful-fallback-uje (`/assistant/health` vraca `llmReachable=false`,
Arbitro FAB prikazuje "Offline" badge umesto chat panel-a). Celine 1-5 rade
normalno bez Arbitro-a.

```bash
# Apply sve OSIM 11-arbitro/
kubectl apply -f k8s/00-namespace.yaml \
              -f k8s/01-configmaps/ \
              -f k8s/04-databases/ \
              -f k8s/05-services/ \
              -f k8s/06-ingress/ \
              -f k8s/07-monitoring/ \
              -f k8s/08-jobs/ \
              -f k8s/09-spark/ \
              -f k8s/10-networkpolicies/
```

**Opcija B: deploy bez ollama (samo CPU sidecari).** Wikipedia, RAG, Kokoro, Whisper rade
na CPU. Arbitro ce graceful-fallback-ovati na "LLM nedostupan" ali tools ostaju
dostupni (rucni RAG/Wiki kroz `/assistant/tools/*` endpoints):

```bash
kubectl apply -f k8s/11-arbitro/wikipedia-service-deployment.yaml \
              -f k8s/11-arbitro/wikipedia-service-svc.yaml \
              -f k8s/11-arbitro/rag-service-deployment.yaml \
              -f k8s/11-arbitro/rag-service-svc.yaml \
              -f k8s/11-arbitro/kokoro-tts-deployment.yaml \
              -f k8s/11-arbitro/kokoro-tts-svc.yaml \
              -f k8s/11-arbitro/whisper-stt-deployment.yaml \
              -f k8s/11-arbitro/whisper-stt-svc.yaml
# (NE primenjivati ollama-deployment.yaml)
```

**Opcija C: pun deploy sa GPU node-om.** Ako klaster ima NVIDIA GPU node (sa
nvidia.com/gpu device plugin instaliranim):

```bash
kubectl apply -f k8s/11-arbitro/
```

Ollama Deployment ima `nodeSelector: nvidia.com/gpu.present: "true"` — DevOps tim
mora labelovati GPU node-ove tako: `kubectl label node <gpu-node> nvidia.com/gpu.present=true`.
Bez label-a, Ollama pod ostaje `Pending` (nema match-ujuceg node-a).

## Service names — preusmeravanje sa banka-core

`banka-core-config.yaml` ima placeholder URL-ove:
- `ARBITRO_BASE_URL: "http://ollama-svc:11434/v1"`
- `ARBITRO_WIKI_URL: "http://wikipedia-service-svc:8090"`
- `ARBITRO_RAG_URL: "http://rag-service-svc:8091"`
- `ARBITRO_KOKORO_URL: "http://kokoro-tts-svc:8092"`
- `ARBITRO_WHISPER_URL: "http://whisper-stt-svc:8093"`

Service name-ovi ovde tacno match-uju te placeholder-e. Ako sidecar nije deployovan,
HTTP poziv timeout-uje sa connection-refused; BE ima graceful-degradation u svim
`/assistant/*` endpoint-ima.

## RAG spec docs (NIJE ucitan u K8s)

U docker-compose, RAG sidecar auto-indeksira spec docs iz `Info o predmetu/` bind-mount
pri prvom startu (`index_specs.py`). U K8s nema bind-mount-a. RAG sidecar radi ali
ChromaDB ostaje prazan dok ga ne popunis rucno (npr. ConfigMap sa spec content,
init container, ili API poziv `/rag/index`).

Za demo opcija: pokrenuti spec indexing rucno posle deploy-a:
```bash
kubectl exec -n banka2-tim2 deploy/rag-service -- python scripts/index_specs.py
```
(spec docs treba prethodno copy-ovati u pod kroz `kubectl cp` ili init container.)
