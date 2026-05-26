# Banka 2 Tim 2 — Kubernetes Deploy Guide

Kompletna paleta raw YAML manifests-a za fakultetski Kubernetes klaster
(Mudrinic vezba 27.05.2026 + production deploy).

Folder struktura prati K8s redosled apply-a (00 → 10). Svaki podfolder je
samostalan i moze se apply-ovati sa `kubectl apply -f <folder>/`.

```
k8s/
├── 00-namespace.yaml
├── 01-configmaps/        — ConfigMaps (8 fajla, ne-sensitive env)
├── 02-secrets/           — Secret templates (9 .example fajla, NE commit-ovati prave)
├── 03-storage/           — README sa StorageClass napomenama
├── 04-databases/         — StatefulSets za postgres-primary, postgres-replica, trading-db, influxdb, rabbitmq
├── 05-services/          — Deployments + Services + HPA + PDB za 5 app servisa
├── 06-ingress/           — Ingress sa TLS i Mudrinic alias rutama
├── 07-monitoring/        — prometheus + grafana + alertmanager + alert-router
├── 08-jobs/              — banka-core seed + trading-service seed + bootstrap historical data
├── 09-spark/             — 3 ScheduledSparkApplication CRD + operator install guide
├── 10-networkpolicies/   — internal-mesh + db-restrict NetPol
└── README.md             — ovaj fajl
```

## Pre-requisites

Pre apply-a, klaster mora imati:

1. **Kubernetes 1.27+** (testirano na 1.29)
2. **nginx-ingress-controller** instaliran u `ingress-nginx` namespace
   ```bash
   helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
   helm install ingress-nginx ingress-nginx/ingress-nginx -n ingress-nginx --create-namespace
   ```
3. **cert-manager + Let's Encrypt ClusterIssuer** za TLS sertifikat na Ingress-u
   ```bash
   helm repo add jetstack https://charts.jetstack.io
   helm install cert-manager jetstack/cert-manager -n cert-manager --create-namespace --set installCRDs=true
   # Kreiraj ClusterIssuer letsencrypt-prod (ACME HTTP-01 challenge)
   kubectl apply -f - <<EOF
   apiVersion: cert-manager.io/v1
   kind: ClusterIssuer
   metadata: { name: letsencrypt-prod }
   spec:
     acme:
       server: https://acme-v02.api.letsencrypt.org/directory
       email: tim2@raf.rs
       privateKeySecretRef: { name: letsencrypt-prod }
       solvers:
         - http01: { ingress: { class: nginx } }
   EOF
   ```
4. **kubeflow/spark-operator** (vidi `09-spark/spark-operator-install.md`)
5. **StorageClass koji podrzava ReadWriteOnce** (default na vecini cloud providera; on-prem treba local-path-provisioner ili rook-ceph)
6. **metrics-server** za HPA (default postoji na vecini distribucija)
   ```bash
   kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
   ```

## Deploy redosled

### Korak 1 — Namespace + Secrets (RUCNO)

```bash
# Kreiraj namespace
kubectl apply -f 00-namespace.yaml

# Label-uj namespace za NetworkPolicy ingress filter (matchLabels pretrazuje ovo)
kubectl label namespace banka2-tim2 kubernetes.io/metadata.name=banka2-tim2 --overwrite

# Kreiraj sve Secrets iz templates-a (NE commit-uj YAML-ove sa pravim vrednostima!).
# Primer postgres-credentials:
kubectl create secret generic postgres-credentials \
  --from-literal=POSTGRES_PASSWORD=$(openssl rand -hex 16) \
  --from-literal=POSTGRES_TRADING_PASSWORD=$(openssl rand -hex 16) \
  --from-literal=POSTGRES_REPLICATION_PASSWORD=$(openssl rand -hex 16) \
  --from-literal=ANALYTICS_READER_PASSWORD=$(openssl rand -hex 16) \
  -n banka2-tim2

# JWT secret (256-bit base64)
kubectl create secret generic jwt-secret \
  --from-literal=BANKA2_JWT_SECRET=$(openssl rand -base64 32) \
  -n banka2-tim2

# Internal API key (BE inter-service)
kubectl create secret generic internal-api-key \
  --from-literal=BANKA2_INTERNAL_API_KEY=$(openssl rand -hex 32) \
  -n banka2-tim2

# Pogledaj 02-secrets/*.example za ostatak (discord-webhook, partner-tokens,
# external-api-keys, mail-credentials, influxdb-credentials, tls-cert).
```

### Korak 2 — ConfigMaps

```bash
kubectl apply -f 01-configmaps/

# Za banka-core-seed-sql ConfigMap (koristi se u 08-jobs/banka-core-seed-job.yaml):
kubectl create configmap banka-core-seed-sql \
  --from-file=seed.sql=../seed.sql \
  -n banka2-tim2

# Za trading-service-seed-sql ConfigMap:
kubectl create configmap trading-service-seed-sql \
  --from-file=trading-seed.sql=../trading-seed.sql \
  -n banka2-tim2
```

### Korak 3 — Databases (StatefulSets)

```bash
kubectl apply -f 04-databases/

# Cekaj postgres-primary da bude ready (3-5 min na cold start).
kubectl wait --for=condition=ready pod -l app=postgres-primary -n banka2-tim2 --timeout=300s

# Cekaj trading-db
kubectl wait --for=condition=ready pod -l app=trading-db -n banka2-tim2 --timeout=300s

# Influx + RabbitMQ
kubectl wait --for=condition=ready pod -l app=influxdb -n banka2-tim2 --timeout=300s
kubectl wait --for=condition=ready pod -l app=rabbitmq -n banka2-tim2 --timeout=300s
```

### Korak 4 — App services (Deployments)

```bash
kubectl apply -f 05-services/

# Cekaj sve servise da budu ready (Spring Boot start ~60-90s).
kubectl wait --for=condition=available --timeout=300s \
  deployment/banka-core deployment/trading-service deployment/notification-service \
  deployment/api-gateway deployment/frontend \
  -n banka2-tim2
```

### Korak 5 — Ingress + TLS

```bash
kubectl apply -f 06-ingress/

# cert-manager ce automatski izdati Let's Encrypt sertifikat (~30s-2min).
kubectl get certificate -n banka2-tim2 -w
# Expected: banka2-tls-cert  True  banka2-tls-cert  <age>
```

### Korak 6 — Monitoring

```bash
kubectl apply -f 07-monitoring/

# Grafana UI: portforward za inicijalni setup (admin/admin → promeniti lozinku)
kubectl port-forward -n banka2-tim2 svc/grafana-svc 3000:3000
# Otvori http://localhost:3000

# Prometheus UI
kubectl port-forward -n banka2-tim2 svc/prometheus-svc 9090:9090
```

### Korak 7 — Seed Jobs (jednokratno)

```bash
kubectl apply -f 08-jobs/

# Pratiti logove
kubectl logs -n banka2-tim2 job/banka-core-seed -f
kubectl logs -n banka2-tim2 job/trading-service-seed -f
kubectl logs -n banka2-tim2 job/bootstrap-historical-data -f

# Provera da li su Job-ovi zavrsili
kubectl get jobs -n banka2-tim2
# Expected: COMPLETIONS 1/1 za sve 3 Job-a.
```

### Korak 8 — Spark jobs

```bash
# Prvo instaliraj Spark Operator (vidi 09-spark/spark-operator-install.md)
helm install spark-operator spark-operator/spark-operator \
  -n spark-operator --create-namespace \
  --set sparkJobNamespace=banka2-tim2 \
  --set webhook.enable=true \
  --set serviceAccounts.spark.create=true

# Zatim apply ScheduledSparkApplication manifests
kubectl apply -f 09-spark/

# Verify
kubectl get scheduledsparkapplications -n banka2-tim2
# Expected: 3 entries (analytics-daily, price-prediction, fraud-detection)

# Pogledaj sledeci scheduled run timestamp
kubectl describe scheduledsparkapplication analytics-daily -n banka2-tim2 | grep "Next Run"
```

### Korak 9 — NetworkPolicies (security hardening)

**VAZNO:** NetPol-e apply-uj NA KRAJU, tek kada potvrdis da sve radi. Inace
moze blokirati saobracaj i otezati debugging.

```bash
kubectl apply -f 10-networkpolicies/

# Test smoke: frontend mora moci da hituje api-gateway
kubectl exec -n banka2-tim2 deploy/frontend -- curl -sf http://api-gateway-svc/api/health
```

## Verifikacija deploy-a

```bash
# Sve pod-e u namespace-u
kubectl get pods -n banka2-tim2 -o wide

# Sve servise
kubectl get svc -n banka2-tim2

# Ingress + adresa
kubectl get ingress -n banka2-tim2
# IP/host iz ADDRESS kolone → otvori u browser-u sa HTTPS

# HPA stanje
kubectl get hpa -n banka2-tim2

# PDB stanje
kubectl get pdb -n banka2-tim2
```

## Troubleshooting

### Pod-ovi su CrashLoopBackOff
```bash
kubectl describe pod <pod> -n banka2-tim2
kubectl logs <pod> -n banka2-tim2 --previous
```

Cesti razlozi:
- **Secret missing/wrong key** → proveri `kubectl get secrets -n banka2-tim2`
- **DB ne ready** → InitContainer ceka, ali timeout ako primary ne start
- **Insufficient resources** → `kubectl describe node` za alokaciju

### Probe failure (Readiness/Liveness)
- Spring Boot startup spor (Hibernate ddl-auto=update + lazy init) — povecati
  `startupProbe.failureThreshold` (sada 30 × 10s = 5min)
- Database konekcija fail-uje — proveri network policy + DNS resolution

### Ingress 502 Bad Gateway
- Backend service nema endpoints → `kubectl get endpoints api-gateway-svc -n banka2-tim2`
- nginx-ingress upstream timeout → `nginx.ingress.kubernetes.io/proxy-read-timeout`

### Spark Job ne start
- Operator logove → `kubectl logs -n spark-operator deploy/spark-operator`
- `kubectl describe scheduledsparkapplication <name> -n banka2-tim2` (events na dnu)
- ServiceAccount `spark` mora postojati u namespace `banka2-tim2`
- Image pull errors → image `ghcr.io/raf-si-2025/banka-2-spark-jobs:latest` mora biti
  build-ovan + push-ovan na GHCR (spark-cd.yml workflow)

### Cert-manager certificate Pending
- ACME HTTP-01 challenge fail-uje ako DNS A record ne pokazuje na ingress-controller IP
- Rate limit (5 dupl certs / nedeljno) → koristi `letsencrypt-staging` za testing

### HPA "unknown" metrika
- metrics-server nije instaliran ili ne funkcionise → `kubectl top nodes`
- Po default-u metrics-server treba ~30s da prikupi prvu metriku

## Restart / Rolling update

Image update se radi preko CD pipeline-a (svaki push na `main` → novi `:latest`
+ `:sha` tag). Da forsiras pickup:

```bash
kubectl rollout restart deployment/banka-core -n banka2-tim2
kubectl rollout status deployment/banka-core -n banka2-tim2
```

## Cleanup

```bash
# Brisanje samo app servisa (zadrzava bazu)
kubectl delete -f 05-services/

# Full namespace teardown (UNISTAVA SVE, ukljucujuci StatefulSet PVCs!)
kubectl delete namespace banka2-tim2

# PVC ostaju ako StorageClass ima `reclaimPolicy: Retain` - rucno cleanup:
kubectl get pv | grep banka2-tim2
kubectl delete pv <pv-name>
```

## Kontakt

- **DevOps tim:** Luka Stojiljkovic (FE Lead + DevOps), kontakt na Discord-u.
- **Backend lead:** Aleksa Vucinic / Andjela Vilcek (T1-T3).
- **K8s vezba:** Mudrinic 27.05.2026 u terminu.
