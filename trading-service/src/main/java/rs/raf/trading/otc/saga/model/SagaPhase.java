package rs.raf.trading.otc.saga.model;

public enum SagaPhase {
    F1(1), F2(2), F3(3), F4(4), F5(5);
    private final int step;
    SagaPhase(int step) { this.step = step; }
    public int step() { return step; }
    public static SagaPhase ofStep(int step) {
        for (SagaPhase p : values()) if (p.step == step) return p;
        throw new IllegalArgumentException("Nepoznata SAGA faza: " + step);
    }
}
