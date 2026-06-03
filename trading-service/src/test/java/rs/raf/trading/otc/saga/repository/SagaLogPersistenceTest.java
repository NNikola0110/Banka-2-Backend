package rs.raf.trading.otc.saga.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.otc.saga.model.SagaLog;
import rs.raf.trading.otc.saga.model.SagaLogEntry;
import rs.raf.trading.otc.saga.model.SagaStatus;
import rs.raf.trading.otc.saga.model.SagaStepKind;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip persistence test za SagaLog + SagaLogEntryListConverter.
 *
 * NAPOMENA: Spring Boot 4 je uklonio @DataJpaTest iz default test-autoconfigure
 * modula — koristimo @SpringBootTest sa H2 (application-test.properties), isti
 * obrazac kao banka2_bek InterbankOtc*RepositoryTest.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SagaLogPersistenceTest {

    @Autowired
    private SagaLogRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void persistsAndReloadsSagaLogWithJsonEntries() {
        SagaLog saga = new SagaLog();
        saga.setSagaId("saga-roundtrip-1");
        saga.setContractId(42L);
        saga.setStatus(SagaStatus.RUNNING);
        saga.setCurrentStep(2);
        saga.append(SagaLogEntry.ok(1, SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.err(2, SagaStepKind.COMPENSATE, "boom"));

        repository.saveAndFlush(saga);
        assertThat(saga.getId()).isNotNull();
        assertThat(saga.getCreatedAt()).isNotNull();
        assertThat(saga.getUpdatedAt()).isNotNull();

        // detach so the reload truly comes from the DB column (JSON survived)
        entityManager.flush();
        entityManager.clear();

        Optional<SagaLog> reloaded = repository.findBySagaId("saga-roundtrip-1");
        assertThat(reloaded).isPresent();
        SagaLog r = reloaded.get();
        assertThat(r.getContractId()).isEqualTo(42L);
        assertThat(r.getStatus()).isEqualTo(SagaStatus.RUNNING);
        assertThat(r.getCurrentStep()).isEqualTo(2);
        assertThat(r.getEntries()).hasSize(2);

        SagaLogEntry first = r.getEntries().get(0);
        assertThat(first.phase()).isEqualTo(1);
        assertThat(first.kind()).isEqualTo(SagaStepKind.FORWARD);
        assertThat(first.outcome()).isEqualTo("ok");
        assertThat(first.message()).isNull();
        assertThat(first.at()).isNotBlank();

        SagaLogEntry second = r.getEntries().get(1);
        assertThat(second.phase()).isEqualTo(2);
        assertThat(second.kind()).isEqualTo(SagaStepKind.COMPENSATE);
        assertThat(second.outcome()).isEqualTo("err");
        assertThat(second.message()).isEqualTo("boom");
    }

    @Test
    void findByContractIdAndStatusInWork() {
        SagaLog a = new SagaLog();
        a.setSagaId("saga-find-a");
        a.setContractId(7L);
        a.setStatus(SagaStatus.COMPENSATING);
        a.setCurrentStep(3);
        repository.saveAndFlush(a);

        assertThat(repository.findByContractId(7L)).extracting(SagaLog::getSagaId).contains("saga-find-a");
        assertThat(repository.findByStatusIn(java.util.List.of(SagaStatus.COMPENSATING)))
                .extracting(SagaLog::getSagaId).contains("saga-find-a");
    }
}
