package rs.raf.trading.common.dto;

/**
 * Standardni {message} envelope za greske trgovinskih kontrolera.
 * Kopija banka-core auth.dto.MessageResponseDto (copy-first ekstrakcija) —
 * koriste ga ListingController (Swagger @Schema) i ListingExceptionHandler.
 *
 * <p><b>R5 1879 (duplikat — DOCUMENT-ACCEPTED):</b> identicna banka-core
 * {@code auth.dto.MessageResponseDto}. Konsolidacija u {@code banka2-contracts}
 * je odbacena za mehanicki cleanup batch jer DTO figurise u Swagger @Schema-ma i
 * serijalizovanim odgovorima kroz vise modula (~195 referenci / 23 fajla) — package
 * promena rizikuje kontrakt/serijalizaciju. Polje-ekvivalencija se pin-uje
 * karakterizacionim testom ({@code MessageResponseDtoContractPinTest}); ako se polja
 * razidju, test pukne. Pri sledecoj namernoj refaktorizaciji preseliti u contracts.
 */
public class MessageResponseDto {

    private String message;

    public MessageResponseDto() {
    }

    public MessageResponseDto(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
