# Chaos-testing runbook — SAGA / 2PC resilience (SG-09a / SG-10 / SG-11)

Mrezni chaos scenariji za Model-B OTC-exercise SAGA-u (intra-bank opcioni exercise
kroz `OtcExerciseSagaOrchestrator`). Pokriva tri mane koje audit (Celina 5 /
`SAGA_test.pdf`) trazi a koje **nije moguce verno reprodukovati u in-process testu**:

| ID        | Mana                                            | Sta dokazuje                                                        |
|-----------|-------------------------------------------------|--------------------------------------------------------------------|
| **SG-09a**| banka-core nedostupan usred SAGA-e              | SAGA kompenzuje / recovery dovede u terminal; I1/I2 ocuvani        |
| **SG-10** | mrezni latency (inject-delay) tokom faze        | spor link → read-timeout → kompenzacija ili dovrsenje; bez gubitka |
| **SG-11** | pad koordinatora (trading-service) usred leta   | restart → recovery cita durable `saga_logs` → terminal stanje      |

> **Sta je automatizovano, sta je manuelno:** transport-level fault logika (konekcija
> odbijena / read-timeout → SAGA kompenzuje, novac/akcije ocuvani) je pokrivena
> **automatskim in-process testom** `OtcSagaNetworkChaosInProcessTest` (vidi dno
> dokumenta). PRAVI mrezni chaos protiv zivog stacka (Toxiproxy `down`/`latency`,
> `docker pause`, `docker kill` koordinatora + recovery-po-restart) je **manuelan**
> i izvodi se po procedurama ispod — to zahteva visekontejnerski stack koji je
> namerno van JVM test scope-a (flaky/tezak). Razlog: postojeci invarijant test
> (`OtcSagaInvariantIntegrationTest`) koristi `FakeBankaCoreClient` BAS zato sto
> Docker banka-core nije pouzdano dostupan u testu.

---

## 0. Preduslov: podigni chaos stack

Chaos stack je glavni `docker-compose.yml` + overlay `docker-compose.chaos.yml`.
Overlay umetne **Toxiproxy** izmedju `trading-service`-a i `backend`-a (banka-core) i
upali `SAGA_CHAOS_ENABLED=true` (aktivira `HeaderSagaFaultInjector` → `X-Saga-*`
header-i). Bez overlay-a glavni stack radi netaknut.

```bash
cd Banka-2-Backend
cp .env.example .env            # ako jos nemas — compose trazi POSTGRES_PASSWORD itd.

# Validiraj da overlay parsira (ne dize nista):
docker compose -f docker-compose.yml -f docker-compose.chaos.yml config -q

# Podigni ceo stack + Toxiproxy:
docker compose -f docker-compose.yml -f docker-compose.chaos.yml up -d --build

# Sacekaj da sve bude healthy (banka-core ~60-90s zbog seed-a):
docker compose -f docker-compose.yml -f docker-compose.chaos.yml ps
```

**Wiring (sta overlay menja):**

```
  trading-service ──(BANKACORE_BASE_URL=http://toxiproxy:8666)──> toxiproxy ──> backend:8080
                                                                     │
                                                          admin API :8474 (toxic add/remove)
```

Proxy `bankacore` (listen `:8666` → upstream `backend:8080`) se kreira pri boot-u iz
`chaos/toxiproxy.json` (zdrav link, bez toxic-a). Toxic-e dodajemo runtime-om.

**Toxic-e dodajemo na jedan od dva nacina** (oba ekvivalentna):

A) `toxiproxy-cli` UNUTAR kontejnera:
```bash
docker compose -f docker-compose.yml -f docker-compose.chaos.yml exec toxiproxy \
  /toxiproxy-cli toxic add bankacore --type latency --attribute latency=5000 --upstream
```

B) Toxiproxy admin HTTP API sa hosta (port 8474 izlozen):
```bash
curl -s -X POST http://localhost:8474/proxies/bankacore/toxics \
  -d '{"type":"latency","stream":"upstream","attributes":{"latency":5000}}'
```

**Test podaci.** OTC exercise zahteva ACTIVE ugovor ciji si ti kupac, sa pokrivenom
rezervacijom. Seed (`trading-seed.sql`) puni `otc_contracts` (vidi CLAUDE.md
"OTC popunjen": `otc_contracts=6`). Pribavi token + contractId:

```bash
# Login (klijent sa TRADE_STOCKS) — vrati JWT:
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"stefan.jovanovic@gmail.com","password":"Klijent12345"}' | jq -r .accessToken)

# Nadji ACTIVE ugovor gde je ulogovani kupac:
curl -s http://localhost:8080/api/otc/contracts -H "Authorization: Bearer $TOKEN" | jq
```

Za sve procedure ispod: `CID` = contractId, `ACC` = buyerAccountId (kupcev racun u
valuti listinga). Exercise poziv (kroz api-gateway na 8080):

```bash
curl -s -X POST "http://localhost:8080/api/otc/contracts/$CID/exercise?buyerAccountId=$ACC" \
  -H "Authorization: Bearer $TOKEN"
# → {"id":...,"status":...,"sagaId":"...","sagaStatus":"...","currentStep":N}
```

Polling terminalnog stanja:
```bash
curl -s "http://localhost:8080/api/otc/saga/$SAGA_ID" -H "Authorization: Bearer $TOKEN" | jq
# → {"sagaId":"...","status":"COMPLETED|COMPENSATED|FAILED","currentStep":N,"log":[...]}
```

---

## SG-09a — banka-core nedostupan usred SAGA-e

**Scenario (SAGA_test.pdf SG-09a):** Tokom exercise SAGA-e banka-core (novcane noge
F1/F3 + resolve racuna) postane nedostupan. Pozivi padaju sa connection-refused /
read-timeout. SAGA mora da **kompenzuje** primenjene faze i zavrsi u terminalu
(`COMPENSATED`) ILI, ako pad pogodi pre bilo kog bocnog efekta, da odbije bez
efekata. Recovery scheduler dovrsava zaglavljene instance.

**Ocekivane invarijante:** **I1** (Σ novca ocuvana — nikakav novac nije kreiran/unisten),
**I2** (akcije ocuvane), I3/I4 (reserved=0 na onome sto je ova saga kreirala),
status ugovora vracen na `ACTIVE`. SAGA dostize terminalni status.

### Varijanta A — `docker compose pause` banka-core (najjednostavnije)

Siroka mana (svaki banka-core poziv visi → read-timeout 30s). Zato umetni delay u
ranu fazu da prozor bude dovoljno sirok da stignes da pauziras.

```bash
# 1) Pokreni exercise sa inject-delay na F3 (sirok prozor pred prvu novcanu nogu).
#    F1 reserve prolazi, F3 commit ceka 30s — pauziraj banka-core u tom prozoru.
SAGA=$(curl -s -X POST "http://localhost:8080/api/otc/contracts/$CID/exercise?buyerAccountId=$ACC" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'X-Saga-Inject-Delay: F3:30000' | jq -r .sagaId) &

# 2) Brzo (u roku od ~25s) pauziraj banka-core:
docker compose -f docker-compose.yml -f docker-compose.chaos.yml pause backend

# 3) Saga F3 commitFunds sad ide u read-timeout (5..30s) → ResourceAccessException →
#    orkestrator hvata RuntimeException → kompenzacija C2/C1. Ako kompenzator
#    takodje treba banka-core (C1 release) i on visi → COMPENSATING (durable).

# 4) Odpauziraj banka-core da recovery moze da dovrsi kompenzaciju:
docker compose -f docker-compose.yml -f docker-compose.chaos.yml unpause backend

# 5) Recovery scheduler (SagaRecoveryScheduler) periodicno sweep-uje COMPENSATING.
#    Sacekaj jedan-dva sweep-a, pa proveri terminal:
curl -s "http://localhost:8080/api/otc/saga/$SAGA" -H "Authorization: Bearer $TOKEN" | jq .status
# Ocekivano: "COMPENSATED" (ili "COMPLETED" ako je pad bio posle F5 a pre flip-a).
```

### Varijanta B — Toxiproxy `down` na banka-core link (deterministicki, samo novcane noge)

`down` toxic obara link odmah (connection refused), bez cekanja read-timeout-a. Pogadja
SAMO banka-core pozive (DB/Influx/Rabbit nisu na ovom proxy-ju).

```bash
# 1) Obori banka-core link:
curl -s -X POST http://localhost:8474/proxies/bankacore \
  -d '{"enabled":false}'

# 2) Pokreni exercise — F1 reserveFunds odmah pada (connection refused) → SAGA
#    kompenzuje (C1 no-op jer rezervacija nije ni nastala) → COMPENSATED.
curl -s -X POST "http://localhost:8080/api/otc/contracts/$CID/exercise?buyerAccountId=$ACC" \
  -H "Authorization: Bearer $TOKEN" | jq

# 3) Vrati link:
curl -s -X POST http://localhost:8474/proxies/bankacore -d '{"enabled":true}'
```

**Verifikacija (oba varijanta):**
- `GET /otc/saga/{id}` → `status` je terminalan (`COMPENSATED`/`COMPLETED`).
- Ugovor: `GET /otc/contracts` → status `ACTIVE` (ako COMPENSATED).
- I1/I2: uporedi salda kupca/prodavca + pozicije pre/posle preko Adminer-a
  (`http://localhost:9001`, server `db` za novac, `trading_db` za pozicije) —
  Σ novca i Σ akcija nepromenjeni; nema "висеће" rezervacije koju je ova saga kreirala.

---

## SG-10 — mrezni latency tokom faze

**Scenario (SAGA_test.pdf SG-10):** Banka-core link je spor (npr. 5s latency po pozivu).
SAGA ili dovrsi sporo (ispod read-timeout-a) ili, ako latency > read-timeout (30s),
poziv pukne kao timeout → kompenzacija. Oba ishoda su validna dok invarijante drze.

> **Tajming je bitan.** `BankaCoreClientConfig`: connect-timeout **5s**, read-timeout
> **30s**. Latency **< 30s** → SAGA uspe (samo sporo). Latency **> 30s** → read-timeout
> → ResourceAccessException → kompenzacija (efektivno isto sto i SG-09a, ali izazvano
> latencijom umesto pada).

```bash
# A) Spor-ali-uspesan (5s po banka-core pozivu — SAGA COMPLETED, samo sporije):
docker compose -f docker-compose.yml -f docker-compose.chaos.yml exec toxiproxy \
  /toxiproxy-cli toxic add bankacore --type latency --attribute latency=5000 --upstream

time curl -s -X POST "http://localhost:8080/api/otc/contracts/$CID/exercise?buyerAccountId=$ACC" \
  -H "Authorization: Bearer $TOKEN" | jq .sagaStatus
# Ocekivano: "COMPLETED" (svaka od ~5 banka-core nogu +5s → ukupno vidno sporije).

# Ukloni toxic:
docker compose -f docker-compose.yml -f docker-compose.chaos.yml exec toxiproxy \
  /toxiproxy-cli toxic remove bankacore --toxicName latency_upstream

# B) Latency iznad read-timeout (35s > 30s) → timeout → kompenzacija:
docker compose -f docker-compose.yml -f docker-compose.chaos.yml exec toxiproxy \
  /toxiproxy-cli toxic add bankacore --type latency --attribute latency=35000 --upstream

curl -s -X POST "http://localhost:8080/api/otc/contracts/$CID/exercise?buyerAccountId=$ACC" \
  -H "Authorization: Bearer $TOKEN" | jq .sagaStatus
# Ocekivano: "COMPENSATED" (read-timeout na prvoj novcanoj nozi → rollback).

docker compose -f docker-compose.yml -f docker-compose.chaos.yml exec toxiproxy \
  /toxiproxy-cli toxic remove bankacore --toxicName latency_upstream
```

**Alternativa bez Toxiproxy-ja** (in-app delay, ne pravi mrezni latency ali siri prozor):
`-H 'X-Saga-Inject-Delay: F3:5000'` ubaci `Thread.sleep(5000)` pre F3 (vidi
`HeaderSagaFaultInjector.maybeDelay`). Korisno za SG-11 (vidi dole) i za rucni
race-window, NE za pravi mrezni latency.

**Ocekivane invarijante:** I1/I2 ocuvani u oba ishoda; ako COMPENSATED → ugovor `ACTIVE`,
nema delimicnih efekata; ako COMPLETED → ugovor `EXERCISED`, novac/akcije premesteni tacno jednom.

---

## SG-11 — pad koordinatora (trading-service) usred leta

**Scenario (SAGA_test.pdf SG-11):** trading-service (SAGA koordinator) padne U SREDINI
exercise-a — posle nekih primenjenih faza, pre flip-a u terminal. Posle restart-a,
`SagaStartupRecovery` + `SagaRecoveryScheduler` procitaju durable `saga_logs`
(write-ahead RUNNING red + per-faza progres kroz `REQUIRES_NEW` writer) i dovedu
zaglavljenu SAGA-u do terminalnog stanja.

**Ocekivane invarijante:** terminal `COMPLETED` ILI `COMPENSATED` — oba validna dok
**I1** (novac) i **I2** (akcije) drze. Bez izgubljenih/duplih efekata (idempotentni
banka-core kljucevi po `sagaId`+grani). Pad izmedju F3 commit i credit → recovery
partial-aware kompenzuje (vidi `f3CommitDone`/`f3CreditDone` flag-ove).

```bash
# 1) Pokreni exercise sa SIROKIM prozorom — inject-delay na F3 (30s) da stignes da
#    ubijes koordinatora dok je F1 (reserve) vec primenjen a F3 (commit) jos ceka.
#    Pusti u pozadini (-d ne radi za curl; koristi & ili drugi terminal):
( curl -s -X POST "http://localhost:8080/api/otc/contracts/$CID/exercise?buyerAccountId=$ACC" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'X-Saga-Inject-Delay: F3:30000' >/tmp/sg11.out 2>&1 ) &

# 2) Brzo zabelezi sagaId iz durable loga (RUNNING red je vec commit-ovan kroz
#    REQUIRES_NEW writer cim je SAGA krenula) — preko Adminer-a (trading_db →
#    saga_logs, status=RUNNING) ili sacekaj /tmp/sg11.out ako stignes.

# 3) UBI koordinatora usred leta (SIGKILL, ne graceful) dok F3 jos visi u delay-u:
docker kill -s KILL banka2_trading
# (ili: docker compose -f docker-compose.yml -f docker-compose.chaos.yml kill -s KILL trading-service)

# 4) In-flight HTTP zahtev je mrtav; SAGA je zaglavljena u saga_logs kao RUNNING
#    (F1 forward "ok" durable, F3 nikad zavrsen). Restartuj koordinatora:
docker compose -f docker-compose.yml -f docker-compose.chaos.yml up -d trading-service

# 5) Pri boot-u SagaStartupRecovery pozove recoverOnce() → nadje RUNNING saga_logs
#    red → orchestrator.recover() gurne ka terminalu (kompenzacija primenjenih faza).
#    Sacekaj da trading-service bude healthy + jedan sweep, pa poll:
SAGA=<sagaId iz koraka 2>
curl -s "http://localhost:8080/api/otc/saga/$SAGA" -H "Authorization: Bearer $TOKEN" | jq .status
# Ocekivano: "COMPENSATED" (recovery bira sigurnu kompenzacionu granu osim ako su
# SVE faze F1..F5 vec primenjene → tada "COMPLETED").
```

**Verifikacija:**
- `GET /otc/saga/{id}` → terminal (`COMPENSATED` ili `COMPLETED`), NE ostaje `RUNNING`.
- `saga_logs.log[]` (kroz polling `log` ili Adminer) pokazuje forward "ok" za F1
  (+ eventualno parcijalni F3 flag-ovi) i kompenzacione "ok" zapise.
- I1/I2 preko Adminer-a: novac + akcije ocuvani; ugovor vracen na `ACTIVE` (ako COMPENSATED).

**Zasto recovery radi i posle SIGKILL:** RUNNING red i per-faza progres se pisu kroz
`SagaLogWriter` u `REQUIRES_NEW` transakciji (write-ahead) — commit-uju se ODMAH,
nezavisno od outer exercise tx-a. Zato prezive pad koordinatora; `findByStatusIn`
ih nalazi posle restart-a.

---

## Teardown

```bash
# Ukloni sve toxic-e (ako su ostali):
curl -s http://localhost:8474/proxies/bankacore/toxics | jq -r '.[].name' | while read t; do
  curl -s -X DELETE "http://localhost:8474/proxies/bankacore/toxics/$t"; done

# Spusti chaos stack:
docker compose -f docker-compose.yml -f docker-compose.chaos.yml down

# (Resetuj seed/bazu samo ako su procedure prljale stanje:)
docker compose -f docker-compose.yml -f docker-compose.chaos.yml down -v
```

---

## Automatizovano vs manuelno — sazetak

| Scenario | Pokrice                                                                                  | Gde                                                                 |
|----------|------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| SG-09a   | **Automatski** (transport fault → kompenzacija, I1/I2) + **manuelno** (pravi `pause`/`down`) | `OtcSagaNetworkChaosInProcessTest` + ovaj runbook                  |
| SG-10    | **Automatski** (read-timeout-style fault na fazi → kompenzacija) + **manuelno** (Toxiproxy latency) | `OtcSagaNetworkChaosInProcessTest` + ovaj runbook                  |
| SG-11    | **Manuelno** (pravi `docker kill` + restart + recovery). Logika recovery-ja je u-process pokrivena. | ovaj runbook + `OtcSagaInvariantIntegrationTest` (I8) + `SagaRecoveryServiceTest` |

### Sta tacno radi automatski test (`OtcSagaNetworkChaosInProcessTest`)

Komplementaran je postojecem `OtcSagaInvariantIntegrationTest` (koji forsira
**HTTP-level** i **business-level** mane: `X-Saga-Force-Fail`, `failCreditFor`). Mrezni
chaos (SG-09a/SG-10) se na nivou klijenta manifestuje kao **transport izuzetak**
(`org.springframework.web.client.ResourceAccessException` — socket connection refused /
read-timeout), a NE kao `BankaCoreClientException(5xx)` sa HTTP statusom. Tu manu
postojeci `FakeBankaCoreClient.failNextCredit()` NE simulira (on baca HTTP 503).

Zato chaos test koristi `NetworkChaosBankaCoreClient` (podklasa `FakeBankaCoreClient`)
koja na ciljanoj fazi baca `ResourceAccessException` (kao da je Toxiproxy `down`/
`latency>read-timeout` na tom pozivu) i dokazuje:

1. **SG-09a/F1 reserve transport-fail** → SAGA kompenzuje pre bilo kog efekta →
   `COMPENSATED`, `post.totalMoney == pre`, akcije netaknute, ugovor `ACTIVE`.
2. **SG-10/F3 commit transport-timeout** (posle uspele F1 rezervacije) → SAGA
   kompenzuje → I1 ocuvan (C1 oslobadja rezervaciju koju je F1 kreirao), ugovor `ACTIVE`.

To zatvara rupu "transport-level fault path nije pokriven nijednim automatskim testom"
bez flaky visekontejnerskog Toxiproxy-Testcontainers setup-a.
