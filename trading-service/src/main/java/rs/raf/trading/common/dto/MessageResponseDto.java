package rs.raf.trading.common.dto;

/**
 * Standardni {message} envelope za greske trgovinskih kontrolera.
 * Kopija banka-core auth.dto.MessageResponseDto (copy-first ekstrakcija) —
 * koriste ga ListingController (Swagger @Schema) i ListingExceptionHandler.
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
