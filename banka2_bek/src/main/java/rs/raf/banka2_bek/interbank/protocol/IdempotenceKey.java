package rs.raf.banka2_bek.interbank.protocol;

import jakarta.validation.constraints.Size;

/**
 * Spec ref: protokol §2.2 Idempotence keys
 *
 * Obavezno polje na svakoj poruci izmedju banaka. Banka koja salje generise
 * locallyGeneratedKey (max 64 bajta), banka koja prima ga trajno belezi i
 * vraca isti odgovor pri retry-u (at-most-once semantika preko §2.9).
 *
 * Napomena: @Size se ovde dokumentuje ali se NE oslanja na Spring MVC @Valid
 * jer se ovaj record deserijalizuje via objectMapper.convertValue() a ne
 * via Spring MVC binding. Validacija duzine se radi eksplicitno u
 * InterbankInboundController (MAX_KEY_LENGTH = 64).
 */
public record IdempotenceKey(
        int routingNumber,
        @Size(max = 64) String locallyGeneratedKey
) {}
