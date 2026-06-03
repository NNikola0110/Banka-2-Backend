package rs.raf.banka2_bek.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R7 observability fix: dokazuje da {@link BusinessMetrics} REGISTRUJE 7 domenskih
 * metrika i da {@code record*()} pozivi STVARNO inkrementiraju seriju (pre fix-a su
 * Counter-i bili registrovani ali nikad inkrementirani → vrednost trajno 0).
 */
class BusinessMetricsTest {

    private MeterRegistry registry;
    private BusinessMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new BusinessMetrics(registry);
    }

    private double count(String name) {
        return registry.get(name).counter().count();
    }

    private double count(String name, String tagKey, String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).counter().count();
    }

    @Test
    void allCountersAreRegistered() {
        assertThat(registry.find("banka2_login_success_total").counter()).isNotNull();
        assertThat(registry.find("banka2_login_failure_total").counter()).isNotNull();
        assertThat(registry.find("banka2_rate_limit_hit_total").counter()).isNotNull();
        assertThat(registry.find("banka2_payments_executed_total").counter()).isNotNull();
        assertThat(registry.find("banka2_otc_contracts_created_total").counter()).isNotNull();
        assertThat(registry.find("banka2_interbank_inbound_total").counter()).isNotNull();
        // Outbound nosi status tag — dve serije (sent/failed) pod istim imenom.
        assertThat(registry.find("banka2_interbank_outbound_total").tag("status", "sent").counter()).isNotNull();
        assertThat(registry.find("banka2_interbank_outbound_total").tag("status", "failed").counter()).isNotNull();
    }

    @Test
    void loginSuccessIncrements() {
        assertThat(count("banka2_login_success_total")).isZero();
        metrics.recordLoginSuccess();
        metrics.recordLoginSuccess();
        assertThat(count("banka2_login_success_total")).isEqualTo(2.0);
    }

    @Test
    void loginFailureIncrements_feedsBruteForceAlert() {
        metrics.recordLoginFailure();
        assertThat(count("banka2_login_failure_total")).isEqualTo(1.0);
    }

    @Test
    void rateLimitHitIncrements_feedsFloodAlert() {
        metrics.recordRateLimitHit();
        metrics.recordRateLimitHit();
        metrics.recordRateLimitHit();
        assertThat(count("banka2_rate_limit_hit_total")).isEqualTo(3.0);
    }

    @Test
    void paymentExecutedIncrements() {
        metrics.recordPaymentExecuted();
        assertThat(count("banka2_payments_executed_total")).isEqualTo(1.0);
    }

    @Test
    void otcContractCreatedIncrements() {
        metrics.recordOtcContractCreated();
        assertThat(count("banka2_otc_contracts_created_total")).isEqualTo(1.0);
    }

    @Test
    void interbankInboundIncrements() {
        metrics.recordInterbankInbound();
        assertThat(count("banka2_interbank_inbound_total")).isEqualTo(1.0);
    }

    @Test
    void interbankOutboundSentAndFailedAreSeparateSeries() {
        metrics.recordInterbankOutboundSent();
        metrics.recordInterbankOutboundFailed();
        metrics.recordInterbankOutboundFailed();
        assertThat(count("banka2_interbank_outbound_total", "status", "sent")).isEqualTo(1.0);
        assertThat(count("banka2_interbank_outbound_total", "status", "failed")).isEqualTo(2.0);
    }
}
