package rs.raf.banka2_bek.fraud;

import java.util.List;

/**
 * [W3-T2] Stranicen response wrapper za {@link FraudAlertDto}.
 *
 * <p>Razlog vlastitog rekorda umesto Spring {@code Page<T>}: Spring Boot
 * od verzije 3.2 emituje warning ("Serializing PageImpl instances as-is is
 * not supported") i Jackson default ObjectMapper-a u {@code standaloneSetup}
 * baca {@code UnsupportedOperationException} pri serializaciji {@code PageImpl}.
 * Custom rekord ima samo polja koje FE koristi (Page totalPages metadata
 * korisno za pagination kontrolu).
 */
public record FraudAlertPageDto(
        List<FraudAlertDto> content,
        long totalElements,
        int totalPages,
        int number,
        int size
) {
}
