package rs.raf.trading.otc.saga.fault;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import rs.raf.trading.otc.saga.model.SagaPhase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit testovi za HeaderSagaFaultInjector — cita X-Saga-* headere iz
 * (Mock)HttpServletRequest-a. Bean je @ConditionalOnProperty(saga.chaos.enabled),
 * ali ga ovde instanciramo direktno (ne kroz Spring), pa profil ne uticeti.
 */
class HeaderSagaFaultInjectorTest {

    @Test
    void forceFailMatchesNamedPhaseAndDefaultKindBefore() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Saga-Force-Fail", "F3");
        HeaderSagaFaultInjector injector = new HeaderSagaFaultInjector(req);

        // F3 "before" -> throws (kind defaults to "before"): failure precedes side effects,
        // pa orchestrator kompenzuje samo C2,C1 i vraca stanje (SAGA_test SG-05).
        assertThatThrownBy(() -> injector.maybeFailForward(SagaPhase.F3, "before"))
                .isInstanceOf(SagaFaultException.class)
                .hasMessageContaining("F3");

        // F3 "after" -> does NOT throw (default wantKind=before != after)
        assertThatCode(() -> injector.maybeFailForward(SagaPhase.F3, "after"))
                .doesNotThrowAnyException();

        // different phase -> does NOT throw
        assertThatCode(() -> injector.maybeFailForward(SagaPhase.F2, "before"))
                .doesNotThrowAnyException();
    }

    @Test
    void forceFailHonorsExplicitKindBefore() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Saga-Force-Fail", "F1");
        req.addHeader("X-Saga-Force-Fail-Kind", "before");
        HeaderSagaFaultInjector injector = new HeaderSagaFaultInjector(req);

        assertThatThrownBy(() -> injector.maybeFailForward(SagaPhase.F1, "before"))
                .isInstanceOf(SagaFaultException.class);
        assertThatCode(() -> injector.maybeFailForward(SagaPhase.F1, "after"))
                .doesNotThrowAnyException();
    }

    @Test
    void forceFailIgnoredWhenHeaderAbsent() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        HeaderSagaFaultInjector injector = new HeaderSagaFaultInjector(req);
        assertThatCode(() -> injector.maybeFailForward(SagaPhase.F3, "after"))
                .doesNotThrowAnyException();
    }

    @Test
    void compensateFailCountdownExhaustsAfterConfiguredTimes() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Saga-Compensate-Fail", "C2");
        req.addHeader("X-Saga-Compensate-Fail-Times", "1");
        HeaderSagaFaultInjector injector = new HeaderSagaFaultInjector(req);

        // first attempt throws, then countdown exhausted -> second does not
        assertThatThrownBy(() -> injector.maybeFailCompensator("saga-1", SagaPhase.F2))
                .isInstanceOf(SagaFaultException.class)
                .hasMessageContaining("C2");
        assertThatCode(() -> injector.maybeFailCompensator("saga-1", SagaPhase.F2))
                .doesNotThrowAnyException();
    }

    @Test
    void compensateFailDefaultsToOneTime() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Saga-Compensate-Fail", "C4");
        HeaderSagaFaultInjector injector = new HeaderSagaFaultInjector(req);

        assertThatThrownBy(() -> injector.maybeFailCompensator("saga-x", SagaPhase.F4))
                .isInstanceOf(SagaFaultException.class);
        assertThatCode(() -> injector.maybeFailCompensator("saga-x", SagaPhase.F4))
                .doesNotThrowAnyException();
    }

    @Test
    void compensateFailIgnoresDifferentPhase() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Saga-Compensate-Fail", "C2");
        HeaderSagaFaultInjector injector = new HeaderSagaFaultInjector(req);
        assertThatCode(() -> injector.maybeFailCompensator("saga-1", SagaPhase.F3))
                .doesNotThrowAnyException();
    }

    @Test
    void delayParsesNamedPhaseAndSleeps() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Saga-Inject-Delay", "F3:50");
        HeaderSagaFaultInjector injector = new HeaderSagaFaultInjector(req);

        long start = System.currentTimeMillis();
        injector.maybeDelay(SagaPhase.F3);
        long elapsed = System.currentTimeMillis() - start;
        // slept ~50ms for matching phase
        org.assertj.core.api.Assertions.assertThat(elapsed).isGreaterThanOrEqualTo(40L);

        // non-matching phase -> returns immediately
        long start2 = System.currentTimeMillis();
        injector.maybeDelay(SagaPhase.F1);
        long elapsed2 = System.currentTimeMillis() - start2;
        org.assertj.core.api.Assertions.assertThat(elapsed2).isLessThan(40L);
    }
}
