package rs.raf.trading.client;

/** Greska pri pozivu banka-core internog API-ja. httpStatus = status koji je vratio banka-core. */
public class BankaCoreClientException extends RuntimeException {
    private final int httpStatus;

    public BankaCoreClientException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
