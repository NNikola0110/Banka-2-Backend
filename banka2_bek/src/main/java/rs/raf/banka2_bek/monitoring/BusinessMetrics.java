package rs.raf.banka2_bek.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Facade nad custom poslovnim (domenskim) Counter-ima banka-core servisa.
 *
 * <p>Standardne metrike (JVM, HTTP request-ovi, DB connection pool, cache hit rate,
 * Logback log nivoi, Tomcat thread pool, ...) vec automatski expozira Spring Boot
 * Actuator + Micrometer Prometheus registry. Ovde zive iskljucivo DOMENSKI counter-i
 * koji opisuju poslovno ponasanje: login uspeh/neuspeh (input za {@code BruteForceLogin}
 * alert), rate-limit hit-ovi ({@code RateLimitFloodActive}), realizovana placanja,
 * sklopljeni OTC ugovori (banka-core deo) i inter-bank protocol message-i po smeru.
 *
 * <p><b>R7 observability fix (01.06.2026):</b> do sada su Counter-i bili registrovani
 * u zasebnoj praznoj {@code @Configuration} klasi ali ih NIKO nije inkrementirao →
 * 7 mrtvih metrika, a 2 Prometheus alert-a koja se na njih oslanjaju
 * ({@code BruteForceLogin} preko {@code banka2_login_failure_total},
 * {@code RateLimitFloodActive} preko {@code banka2_rate_limit_hit_total}, plus
 * {@code OtcInterbankFailures} preko {@code banka2_interbank_outbound_total{status="failed"}})
 * nikad nisu mogla da okinu jer im je serija uvek bila 0/odsutna.
 *
 * <p>Ova klasa je jedini vlasnik registracije domenskih Counter-a: registruje ih
 * iz {@link MeterRegistry} u konstruktoru (idempotentno — Micrometer dedup-uje po
 * imenu+tag-ovima) i izlaze {@code increment*()} metode koje se pozivaju iz pravih
 * tackica izvrsenja (auth filter/servis, interbank inbound/outbound, payment, OTC).
 *
 * <p>Inkrement je best-effort i nikad ne sme da obori poslovni flow — pozivaoci ga
 * stoga zovu van kriticne putanje (posle commit-a / na kraju metode), a metode su
 * jeftine (atomic add). Sve metrike nose {@code application=banka2_backend} tag iz
 * {@code management.metrics.tags.application} property-ja.
 */
@Component
public class BusinessMetrics {

    private final Counter loginSuccess;
    private final Counter loginFailure;
    private final Counter rateLimitHit;
    private final Counter paymentExecuted;
    private final Counter otcContractCreated;
    private final Counter interbankInbound;
    private final Counter interbankOutboundSent;
    private final Counter interbankOutboundFailed;

    public BusinessMetrics(MeterRegistry registry) {
        this.loginSuccess = Counter.builder("banka2_login_success_total")
                .description("Broj uspesnih login zahteva")
                .register(registry);
        this.loginFailure = Counter.builder("banka2_login_failure_total")
                .description("Broj neuspesnih login zahteva — input za BruteForceLogin alert")
                .register(registry);
        this.rateLimitHit = Counter.builder("banka2_rate_limit_hit_total")
                .description("Broj zahteva odbacenih zbog rate-limit-a (HTTP 429)")
                .register(registry);
        this.paymentExecuted = Counter.builder("banka2_payments_executed_total")
                .description("Broj realizovanih placanja izmedju klijenata")
                .register(registry);
        this.otcContractCreated = Counter.builder("banka2_otc_contracts_created_total")
                .description("Broj sklopljenih OTC opcionih ugovora (lokalni + inter-bank)")
                .register(registry);
        this.interbankInbound = Counter.builder("banka2_interbank_inbound_total")
                .description("Broj primljenih inter-bank zahteva")
                .register(registry);
        // OtcInterbankFailures alert filtrira status="failed", pa outbound Counter
        // nosi status tag ("sent"/"failed") da bi serija po failed-u uopste postojala.
        this.interbankOutboundSent = Counter.builder("banka2_interbank_outbound_total")
                .description("Broj poslatih inter-bank zahteva ka partner banci")
                .tag("status", "sent")
                .register(registry);
        this.interbankOutboundFailed = Counter.builder("banka2_interbank_outbound_total")
                .description("Broj poslatih inter-bank zahteva ka partner banci")
                .tag("status", "failed")
                .register(registry);
    }

    /** Uspesan login (200 OK). */
    public void recordLoginSuccess() {
        loginSuccess.increment();
    }

    /** Neuspesan login (401) — feeds BruteForceLogin alert. */
    public void recordLoginFailure() {
        loginFailure.increment();
    }

    /** Rate-limit hit (429 Too Many Requests) — feeds RateLimitFloodActive alert. */
    public void recordRateLimitHit() {
        rateLimitHit.increment();
    }

    /** Realizovano (COMPLETED) unutarbankarsko placanje. */
    public void recordPaymentExecuted() {
        paymentExecuted.increment();
    }

    /** Sklopljen OTC ugovor (lokalni ili inter-bank). */
    public void recordOtcContractCreated() {
        otcContractCreated.increment();
    }

    /** Primljen inter-bank inbound zahtev (posle X-Api-Key autentifikacije). */
    public void recordInterbankInbound() {
        interbankInbound.increment();
    }

    /** Inter-bank outbound poruka prihvacena od partnera (2xx/202). */
    public void recordInterbankOutboundSent() {
        interbankOutboundSent.increment();
    }

    /** Inter-bank outbound poruka odbijena/neisporucena — feeds OtcInterbankFailures alert. */
    public void recordInterbankOutboundFailed() {
        interbankOutboundFailed.increment();
    }
}
