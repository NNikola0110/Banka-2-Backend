# 03-storage/

**PVC-i su inline u StatefulSet `volumeClaimTemplates`** — vidi `04-databases/`.

K8s StatefulSet automatski kreira PVC po pod-u (PVC name = `<template-name>-<sts-name>-<ordinal>`)
sa `volumeClaimTemplates` deklaracijama. Ne treba zaseban PVC manifest.

DevOps tim mora obezbediti `StorageClass` koji podrzava `ReadWriteOnce` (default na vecini K8s
distribucija — `standard`, `gp2`, `local-path`, itd.).

Verify dostupne StorageClass-e:
```bash
kubectl get storageclass
```

Ako default StorageClass nije dostupan, treba override-ovati `volumeClaimTemplates.spec.storageClassName`
u svakom StatefulSet-u u `04-databases/`.
