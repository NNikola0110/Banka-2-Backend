package rs.raf.trading.otc.saga.fault;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import rs.raf.trading.otc.saga.model.SagaPhase;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cita X-Saga-* headere SAMO kad je profil 'chaos' aktivan (saga.chaos.enabled=true).
 * U release modu ovaj bean ne postoji -> ostaje NoOp -> headeri se ignorisu (spec zahtev).
 */
@Component
@Primary
@ConditionalOnProperty(name = "saga.chaos.enabled", havingValue = "true")
public class HeaderSagaFaultInjector implements SagaFaultInjector {

    private final HttpServletRequest request;       // request-scoped proxy injected into singleton
    private final Map<String, Integer> compensateFailRemaining = new ConcurrentHashMap<>();

    public HeaderSagaFaultInjector(HttpServletRequest request) { this.request = request; }

    @Override public void maybeFailForward(SagaPhase phase, String kind) {
        String fail = header("X-Saga-Force-Fail");           // e.g. "F3"
        if (fail == null || !fail.equalsIgnoreCase("F" + phase.step())) return;
        // Default je "before": nespecifikovan kind znaci da faza pada PRE primene bocnih
        // efekata, pa se kompenzuju samo prethodne uspele faze (C{i-1}..C1) i stanje se
        // vraca na pocetno — tacno kako SAGA_test SG-05/06/07 ocekuju.
        String wantKind = header("X-Saga-Force-Fail-Kind");  // before|after, default "before"
        if (wantKind == null) wantKind = "before";
        if (wantKind.equalsIgnoreCase(kind)) {
            throw new SagaFaultException("X-Saga-Force-Fail " + fail + " (" + kind + ")");
        }
    }

    @Override public void maybeFailForwardMid(SagaPhase phase) {
        String fail = header("X-Saga-Force-Fail-Mid");      // e.g. "F4"
        if (fail == null || !fail.equalsIgnoreCase("F" + phase.step())) return;
        // Pad U SREDINI faze (izmedju dve noge bocnih efekata) — npr. F4: seller dekrement
        // vec primenjen i komitovan, buyer credit jos NIJE → kompenzacija mora ukljuciti C{phase}.
        throw new SagaFaultException("X-Saga-Force-Fail-Mid " + fail);
    }

    @Override public void maybeFailCompensator(String sagaId, SagaPhase phase) {
        String fail = header("X-Saga-Compensate-Fail");      // e.g. "C2"
        if (fail == null || !fail.equalsIgnoreCase("C" + phase.step())) return;
        String key = sagaId + ":C" + phase.step();
        int remaining = compensateFailRemaining.computeIfAbsent(key, k -> {
            String t = header("X-Saga-Compensate-Fail-Times"); // default 1
            return t == null ? 1 : Integer.parseInt(t.trim());
        });
        if (remaining > 0) {
            compensateFailRemaining.put(key, remaining - 1);
            throw new SagaFaultException("X-Saga-Compensate-Fail C" + phase.step()
                    + " (preostalo " + (remaining - 1) + ")");
        }
    }

    @Override public void maybeDelay(SagaPhase phase) {
        String delay = header("X-Saga-Inject-Delay");        // e.g. "F3:5000"
        if (delay == null || !delay.toUpperCase().startsWith("F" + phase.step() + ":")) return;
        long ms = Long.parseLong(delay.substring(delay.indexOf(':') + 1).trim());
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private String header(String name) {
        try { return request.getHeader(name); } catch (Exception e) { return null; }
    }
}
