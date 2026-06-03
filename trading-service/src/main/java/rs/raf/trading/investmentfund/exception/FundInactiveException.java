package rs.raf.trading.investmentfund.exception;

/**
 * R1 485 (P2-cleanup-deadcode-1): baca se kad se invest/withdraw cilja na
 * NEAKTIVAN fond. Stanje resursa konfliktuje sa zahtevom → HTTP 409 CONFLICT,
 * NE 403 FORBIDDEN (403 je za authz-denial; inactive fond nije pitanje permisije
 * nego stanja). Mapira se u {@code InvestmentFundExceptionHandler}.
 *
 * <p>Pre fix-a se bacao generic {@link IllegalStateException} koji je handler
 * mapirao na 403 — pogresno za state-conflict (paralela sa R1 410 /
 * {@code OrderStateConflictException}).
 */
public class FundInactiveException extends RuntimeException {
    public FundInactiveException(String message) {
        super(message);
    }
}
