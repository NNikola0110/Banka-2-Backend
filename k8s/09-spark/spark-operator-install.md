# Spark Operator Install (kubeflow/spark-operator)

Pre primene ScheduledSparkApplication CRD manifests-a iz ovog foldera, klaster
mora imati instaliran **kubeflow/spark-operator** (a-k-a `spark-operator`).
Manifest fajlovi `analytics-daily-sparkapp.yaml`, `price-prediction-sparkapp.yaml`,
`fraud-detection-sparkapp.yaml` koriste CRD `ScheduledSparkApplication`
(`apiVersion: sparkoperator.k8s.io/v1beta2`) koji obezbedjuje operator.

## Install preko Helm-a (preporuceno)

```bash
helm repo add spark-operator https://kubeflow.github.io/spark-operator
helm repo update

# Posebna namespace za operator (ne meša se sa banka2-tim2).
kubectl create namespace spark-operator

helm install spark-operator spark-operator/spark-operator \
  --namespace spark-operator \
  --set sparkJobNamespace=banka2-tim2 \
  --set webhook.enable=true \
  --set serviceAccounts.spark.create=true \
  --set serviceAccounts.spark.name=spark \
  --version 2.0.2
```

Ovo kreira:
- `spark-operator` Deployment u namespace `spark-operator`
- CRDs: `SparkApplication`, `ScheduledSparkApplication`
- ServiceAccount `spark` u namespace `banka2-tim2` (driver pod-ovi koriste)
- RBAC (Role + RoleBinding) koja dozvoljava operator-u da gleda
  CRD instance i kreira pod-ove u namespace `banka2-tim2`
- ValidatingWebhookConfiguration za schema validation

## Verify install

```bash
kubectl get pods -n spark-operator
# Expected: spark-operator-xxxxx 1/1 Running

kubectl get crds | grep sparkoperator
# Expected:
#   scheduledsparkapplications.sparkoperator.k8s.io
#   sparkapplications.sparkoperator.k8s.io

kubectl get sa spark -n banka2-tim2
# Expected: spark   1   <age>
```

## Apply ScheduledSparkApplication manifests

```bash
kubectl apply -f k8s/09-spark/analytics-daily-sparkapp.yaml
kubectl apply -f k8s/09-spark/price-prediction-sparkapp.yaml
kubectl apply -f k8s/09-spark/fraud-detection-sparkapp.yaml

# Verify schedules created
kubectl get scheduledsparkapplications -n banka2-tim2

# Inspect operator log za schedule trigger-e
kubectl logs -n spark-operator deploy/spark-operator -f
```

## Pokretanje on-demand (manual trigger)

Spark-operator nudi `SparkApplication` (jednokratni) pored Scheduled varijante.
Za rucno testiranje, kopiraj template ovde u jednokratni SparkApplication:

```bash
kubectl create -n banka2-tim2 -f - <<EOF
apiVersion: sparkoperator.k8s.io/v1beta2
kind: SparkApplication
metadata:
  name: analytics-daily-manual-$(date +%s)
spec:
  $(yq '.spec.template.spec' k8s/09-spark/analytics-daily-sparkapp.yaml)
EOF
```

Ili jednostavnije: kopiraj inline `spec.template.spec` blok iz Scheduled
varijante u SparkApplication.

## Uninstall

```bash
helm uninstall spark-operator -n spark-operator
kubectl delete namespace spark-operator
kubectl delete crds sparkapplications.sparkoperator.k8s.io scheduledsparkapplications.sparkoperator.k8s.io
```
