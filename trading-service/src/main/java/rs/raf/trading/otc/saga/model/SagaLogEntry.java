package rs.raf.trading.otc.saga.model;

/** Jedan zapis po pokusanom koraku (forward ili kompenzator). */
public record SagaLogEntry(int phase, SagaStepKind kind, String outcome, String message, String at) {
    public static SagaLogEntry ok(int phase, SagaStepKind kind) {
        return new SagaLogEntry(phase, kind, "ok", null, java.time.LocalDateTime.now().toString());
    }
    public static SagaLogEntry err(int phase, SagaStepKind kind, String message) {
        return new SagaLogEntry(phase, kind, "err", message, java.time.LocalDateTime.now().toString());
    }
}
