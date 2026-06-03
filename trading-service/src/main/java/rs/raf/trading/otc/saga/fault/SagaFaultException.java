package rs.raf.trading.otc.saga.fault;

public class SagaFaultException extends RuntimeException {
    public SagaFaultException(String message) { super(message); }
}
