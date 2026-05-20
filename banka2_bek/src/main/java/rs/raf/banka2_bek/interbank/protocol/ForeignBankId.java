package rs.raf.banka2_bek.interbank.protocol;

import jakarta.validation.constraints.Size;

/**
 * Spec ref: protokol §2.3 Foreign object identifiers
 *
 * Strogo: banke koje nisu vlasnik objekta MORAJU `id` tretirati kao opaque
 * (ne interpretirati). Koristi se za identifikaciju klijenata, opcija i
 * OTC pregovora preko granica banaka.
 *
 * Napomena: @Size se ovde dokumentuje ali se NE oslanja na Spring MVC @Valid
 * jer se ovaj record deserijalizuje via objectMapper.convertValue() a ne
 * via Spring MVC binding.
 */
public record ForeignBankId(
        int routingNumber,
        @Size(max = 64) String id
) {}
