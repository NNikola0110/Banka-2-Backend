package rs.raf.banka2.contracts.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Oblik odgovora na gresku za <b>javni / interbank REST</b> sloj
 * ({@code InterbankExceptionHandler} u banka-core).
 *
 * <p><b>Uloga (i razgranicenje od {@link rs.raf.banka2.contracts.internal.InternalErrorDto}):</b>
 * ovaj record nosi gresku ka spoljnim/interbank klijentima, dok je
 * {@code InternalErrorDto(code, message)} oblik greske <em>internog</em>
 * service-to-service API-ja (banka-core + trading
 * {@code InternalApiExceptionHandler}/{@code Internal*Controller}). To su dva
 * namerno razdvojena ugovora; ovaj NIJE jedinstveni izvor istine za sve handlere.
 *
 * <p><b>FE/Mobile ugovor (P1-error-contract-1 / P1-mobile):</b> klijenti parsiraju
 * gresku <em>message-first</em> (vidi {@code formatters.ts#getErrorMessage} i Mobile
 * {@code parseHttpError}). Polje {@code message} je zato OBAVEZNO i nikad se ne
 * preimenuje/uklanja. {@code code} (stabilan masinski kljuc) i {@code timestamp}
 * (ISO-8601) su dodatni, opcioni — izostavljaju se iz JSON-a kad su null
 * ({@code JsonInclude.NON_NULL}) tako da postojeci klijenti koji ih ne citaju
 * nisu pogodjeni.
 *
 * <p>Namerno NE nosi {@code status} u telu — HTTP status je jedini izvor istine za
 * status (telo koje duplira status je redundantno i izvor neslaganja).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorDto(String message, String code, Instant timestamp) {

    /** Samo poruka (najcesci slucaj migracije sa {@code MessageResponseDto}). */
    public static ApiErrorDto of(String message) {
        return new ApiErrorDto(message, null, null);
    }
}
