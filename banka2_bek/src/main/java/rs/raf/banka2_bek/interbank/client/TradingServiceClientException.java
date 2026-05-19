package rs.raf.banka2_bek.interbank.client;

/**
 * Greska pri pozivu trading-service internog {@code /internal/portfolio/**} API-ja.
 * {@code httpStatus} = status koji je vratio trading-service (404 listing/portfolio
 * ne postoji, 409 nedovoljno kolicine, ...). Pozivalac u {@code interbank} paketu
 * je prevodi u prikladan {@code InterbankExceptions} tip.
 */
public class TradingServiceClientException extends RuntimeException {

    private final int httpStatus;

    public TradingServiceClientException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
