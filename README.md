# Banka 2 — Backend

Maven multi-module backend za bankarsku platformu: klijenti i zaposleni, racuni
i kartice, placanja i transferi, berzanska trgovina, OTC opcioni ugovori
(intra + inter-bank), inter-bank 2PC placanja i SAGA exercise, Profit Banke
portal i investicioni fondovi. Projekat iz predmeta **Softversko inzenjerstvo**
na Racunarskom fakultetu 2025/26.

## Moduli

Reactor (`pom.xml`) sadrzi cetiri Maven modula plus nginx api-gateway:

| Modul | Opis |
|-------|------|
| `banka2_bek` | **banka-core** monolit: auth, klijenti/zaposleni, racuni, kartice, placanja, transferi, krediti, inter-bank protokol, notifikacije |
| `trading-service` | berza, orderi, portfolio, listings, OTC, fondovi, porez, aktuari, opcije, margin — sopstvena baza `trading` |
| `notification-service` | RabbitMQ consumer + Gmail SMTP sender (nema HTTP REST endpointe) |
| `banka2-contracts` | deljeni DTO/contract artefakti izmedju servisa |
| `api-gateway` | nginx reverse-proxy; jedini spolja izlozen ulaz (host 8080), path-rutira na servise |

## Tech Stack

- **Java 25** + **Spring Boot 4.0.6**
- **PostgreSQL 16-alpine** — `banka2` (primary + streaming read replica preko `AbstractRoutingDataSource`) i odvojen `trading` za trading-service
- **InfluxDB 2.7-alpine** za OHLCV time-series tickove (vlasnik je trading-service)
- **RabbitMQ 3.13** broker (banka-core → notification-service)
- Spring Security + **jjwt 0.13.0** (HS256) — access + refresh token
- Spring Data JPA / Hibernate
- Springdoc OpenAPI 3.0.3 (Swagger UI), PDFBox 3.0.6, **Bucket4j 8.10.1** rate limit, Caffeine cache
- JUnit 5 + Mockito + AssertJ, JaCoCo 0.8.14 (gate 77% line na `banka2_bek`)

## Pokretanje (Docker)

Sve se pokrece preko Dockera. Kopiraj `.env.example` u `.env` i popuni obavezne
varijable (`POSTGRES_PASSWORD`, `POSTGRES_TRADING_PASSWORD`, `ANALYTICS_READER_PASSWORD`)
— compose pada sa jasnim error-om ako nisu set.

```bash
cp .env.example .env   # pa edituj lozinke
docker compose up -d --build
# Sacekaj seed: docker logs banka2_seed (pise "Seed uspesno ubasen!", ~60-90s)
```

`docker-compose.yml` dize sledece servise:

| Servis | Port (host → kontejner) | Opis |
|--------|-------------------------|------|
| `api-gateway` | 8080 → 80 | nginx — jedini spolja izlozen ulaz; path-rutira na backend/trading |
| `backend` | 8081 → 8080 | banka-core Spring Boot (host port samo za direktan debug; override `BACKEND_HOST_PORT`) |
| `trading-service` | 8082 → 8082 | trading-service Spring Boot |
| `notification-service` | 8083 → 8083 | RabbitMQ consumer + email |
| `db` | 6433 → 5432 | PostgreSQL `banka2` primary |
| `db_replica` | 6434 → 5432 | PostgreSQL streaming replica (read-only routing) |
| `trading_db` | 6435 → 5432 | PostgreSQL `trading` (trading-service baza) |
| `influxdb` | 8086 → 8086 | InfluxDB 2.7 (OHLCV tickovi) |
| `rabbitmq` | 5672 + 15672 | broker + management UI (guest/guest) |
| `adminer` | 9001 → 8080 | web DB admin (override `ADMINER_HOST_PORT`) |
| `seed` / `seed-trading` | – | jednokratno popunjavanje seed podataka, izlaze sa kodom 0 |

Aplikacija je dostupna kroz gateway na `http://localhost:8080`. Klijent (FE/Mobile)
nikad ne gadja `backend`/`trading-service` direktno — sve ide kroz gateway, koji
trgovinske prefikse (`/orders`, `/portfolio`, `/listings`, `/funds`, `/tax`,
`/actuaries`, `/options`, `/margin-accounts`, `/otc`, `/profit-bank`, ...) salje
na `trading-service:8082`, a ostalo na `backend:8080`.

**Override gateway port** (Windows Hyper-V rezervise 8080):

```bash
GATEWAY_HOST_PORT=8088 docker compose up -d
```

### Opcioni stack-ovi

Backend repo ima jos dva nezavisna compose-a (bonus aktivnosti, nisu deo Celina 1-5):

- **Tools** (`Banka-2-Tools/docker-compose.yml`) — Arbitro AI asistent (opciona Celina 6). Bez njega FE pokazuje Arbitro chat dugme kao "Offline", ostatak app-a radi normalno. Vidi `Banka-2-Tools/README.md`.
- **Monitoring** (`monitoring/docker-compose.yml`) — Prometheus + Grafana + AlertManager + Discord webhook bridge (MLA bonus). Vidi `monitoring/README.md`.

Oba se prikljucuju na `banka-2-backend_default` mrezu, pa core stack mora biti gore prvi.

## Testovi

Testovi koriste **H2 in-memory u MODE=PostgreSQL** — ne treba ti baza.

```bash
# Iz root-a reaktora (also-make gradi sibling module):
./banka2_bek/mvnw -f pom.xml verify        # ceo reactor + checkstyle + JaCoCo gate
./banka2_bek/mvnw -pl trading-service -am test
```

Build zahteva **JDK 25** (`JAVA_HOME` mora pokazivati na JDK 25). Ako globalni
`mvn` shim forsira drugu JVM, koristi Maven wrapper (`mvnw`) ili pravi
Maven launcher sa `JAVA_HOME` postavljenim na JDK 25.

JaCoCo coverage report: `target/site/jacoco/index.html`. Excludes: `assistant/**`
(Arbitro Celina 6), `**/dto/**`, `**/config/**`, `**/exception/**`, `**/mapper/**`.

## Arhitektura (banka-core moduli)

```text
rs.raf.banka2_bek/
├── auth/             # Login, JWT (jjwt 0.13.0), password reset, permisije, lockout
├── account/          # Racuni (tekuci, devizni, poslovni)
├── card/             # Kartice + zahtevi za izdavanje + FX provizija
├── client/           # CRUD klijenata, authorized_persons
├── company/          # Pravna lica (isBank / isState flag)
├── currency/         # EUR, USD, RSD, GBP, CHF, JPY, CAD, AUD
├── employee/         # CRUD zaposlenih + aktivacija
├── exchange/         # Kursna lista (Fixer.io)
├── loan/             # Krediti + rate + prevremena otplata
├── payment/          # Placanja + PDF potvrde + primaoci + interbank 2PC
├── transfers/        # Interni + FX transferi (pessimistic lock)
├── transaction/      # Istorija transakcija
├── interbank/        # Inter-bank protokol (2PC + OTC negotiation, X-Api-Key)
├── notification/     # Email (async preko RabbitMQ)
├── otp/              # OTP verifikacija placanja/transfera/ordera
├── persistence/      # AbstractRoutingDataSource (read replica routing)
└── assistant/        # Arbitro AI (Celina 6, opciono — exclude iz JaCoCo)
```

Trgovinski domen (orderi, portfolio, listings, OTC, fondovi, porez, aktuari,
opcije, margin) zivi u **trading-service** modulu sa sopstvenom `trading` bazom.

## Autentifikacija i role

- **JWT HS256** (jjwt 0.13.0). Access token nosi `sub` (email), `role` (ADMIN/EMPLOYEE/CLIENT), `active`.
- FE posle logina fetchuje prave **permisije** sa `GET /employees?email=…` jer JWT nosi samo rolu.
- Server-side logout (`POST /auth/logout`) blacklist-uje JWT u Caffeine cache-u.
- Account lockout: vise uzastopno pogresnih login pokusaja → privremeni lock per email.
- Rate limit: Bucket4j per-IP na auth endpoint-ima (capacity konfigurabilan preko `AUTH_RATE_LIMIT_CAPACITY`).
- Role: **ADMIN** (sve + upravljanje zaposlenima; svaki admin je i supervizor) · **SUPERVISOR** (orderi, aktuari, porez, OTC, fondovi full, Profit Banke) · **AGENT** (employee portal + berza + fondovi discovery) · **CLIENT** (klijentski dashboard, berza, OTC/fondovi sa permisijom) · **FUND** (sistemska role kad supervizor trguje u ime fonda).
- Permisije: `ADMIN`, `SUPERVISOR`, `AGENT`, `TRADE_STOCKS`, `VIEW_STOCKS`, `CREATE_CONTRACTS`, `CREATE_INSURANCE`.

## Seed podaci i test kredencijali

| Tip | Email | Lozinka | Napomena |
|-----|-------|---------|----------|
| Admin | `marko.petrovic@banka.rs` | `Admin12345` | ADMIN + SUPERVISOR; manager Fond 1+2 |
| Admin | `jelena.djordjevic@banka.rs` | `Admin12345` | ADMIN + SUPERVISOR |
| Supervizor | `nikola.milenkovic@banka.rs` | `Zaposleni12` | samo SUPERVISOR (bez ADMIN) |
| Agent | `tamara.pavlovic@banka.rs` | `Zaposleni12` | limit 100K, needApproval=false |
| Klijent | `stefan.jovanovic@gmail.com` | `Klijent12345` | racuni + pozicije + OTC ponude/ugovori |
| Klijent | `milica.nikolic@gmail.com` | `Klijent12345` | racuni + pozicije |
| Klijent | `lazar.ilic@yahoo.com` | `Klijent12345` | racuni + pozicije |
| Klijent | `ana.stojanovic@hotmail.com` | `Klijent12345` | racuni + pozicije |

`seed.sql` puni banka-core (`banka2`); `trading-seed.sql` puni trgovinski domen
(`trading`): listinge, berze, OTC ponude/ugovore, investicione fondove, margin
racune i istorijske ordere.

## API pregled

Kompletna dokumentacija: **Swagger UI** na `http://localhost:8080/swagger-ui.html`
(OpenAPI JSON na `/v3/api-docs`).

- **Auth:** `POST /auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/password_reset/request|confirm`, `/auth-employee/activate`
- **Klijentski portal:** `/accounts/my`, `/cards`, `/payments` (OTP + PDF receipt), `/transfers/internal|fx`, `/listings`, `/orders`, `/otc/**`, `/funds/**`, `/portfolio/**`, `/tax/my`
- **Employee/Supervizor/Admin:** `/employees/**`, `/clients/**`, `/loans/**`, `/orders/{id}/approve|decline`, `/actuaries/**`, `/profit-bank/**`, `/admin/employees/**`
- **Inter-bank (Celina 5):** `POST /interbank` (X-Api-Key) — message types `NEW_TX` / `COMMIT_TX` / `ROLLBACK_TX` + OTC §3 negotiation rute

## Order execution engine

`OrderScheduler` (trading-service) periodicno pokupi APPROVED ordere i izvrsava
ih preko `OrderExecutionService` (random partial fills, AON atomicno, Stop/Stop-limit
aktivacija). Provizije: MARKET `min(14% × cena, $7)`, LIMIT `min(24% × cena, $12)`;
zaposleni i FUND orderi imaju 0. FX komisija 1% kad klijent trguje iz racuna u
drugoj valuti. Rezultat: posle BUY/SELL portfolio se sam azurira — **bez
counterparty korisnika**.

## Cene hartija

`POST /listings/refresh`: stocks preko Alpha Vantage GLOBAL_QUOTE (kljucevi u
rotaciji), forex preko Fixer.io, futures random simulacija. **Test mode** (berza
u test modu) preskace eksterne API-je i koristi GBM simulaciju — `ListingDto.isTestMode`
signalizira FE-u. Tickovi se zapisuju u InfluxDB (`tick-listings` bucket).

## Konfiguracija

Sve postavke su env-driven (vidi `.env.example` + `application.properties`). Najvaznije:

| Env var | Default | Opis |
|---------|---------|------|
| `POSTGRES_PASSWORD` | – (obavezno) | banka-core DB lozinka |
| `POSTGRES_TRADING_PASSWORD` | – (obavezno) | trading DB lozinka |
| `JWT_SECRET` | dev fallback | **MENJAJ U PRODUKCIJI** (min 256-bit) |
| `GATEWAY_HOST_PORT` | `8080` | host port api-gateway-a |
| `BACKEND_HOST_PORT` | `8081` | host port banka-core (debug) |
| `AUTH_RATE_LIMIT_CAPACITY` | `100000` | token bucket cap (10 u prod, 100k za E2E) |
| `EXCHANGE_API_KEY` | – | Fixer.io kursna lista |
| `STOCK_API_KEYS` | – | comma-separated Alpha Vantage kljucevi |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | – | SMTP za async notifikacije |
| `PARTNER1_*` | dev fallback | inter-bank protokol (Tim 1) |
| `INTERNAL_API_KEY` | dev fallback | X-Internal-Key za inter-service pozive |

## Bonus aktivnosti (KT3)

- **MLA monitoring** (Prometheus + Grafana + AlertManager + Discord) — `monitoring/`
- **Read replike** (`db_replica` + `AbstractRoutingDataSource`)
- **Python sidecari** (Arbitro tools, alert-router) — drugi jezik u codebase-u
- **K8s deploy** — manifesti u `k8s/` (vidi `k8s/README.md`)

## Troubleshooting

- **Port 8080 zauzet (Windows Hyper-V)** — `GATEWAY_HOST_PORT=8088 docker compose up -d`, ili `net stop winnat && net start winnat` kao admin
- **Seed nije zavrsio** — `docker logs banka2_seed`; najcesce schema drift (entity ne odgovara seed-u)
- **Alpha Vantage rate limit** — ukljuci test mode za berze u admin portalu (refresh ide na GBM simulaciju)
- **Auth rate limit pukao u Cypress live testovima** — `AUTH_RATE_LIMIT_CAPACITY=100000`. NE setuj `AUTH_RATE_LIMIT_ENABLED=false` (GlobalSecurityConfig zahteva `AuthRateLimitFilter` bean)

## Tim

Banka 2025 Tim 2 — Racunarski fakultet, 2025/26. Predmet: **Softversko inzenjerstvo**.
