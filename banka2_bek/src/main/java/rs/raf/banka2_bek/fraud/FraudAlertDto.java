package rs.raf.banka2_bek.fraud;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * [W3-T2] Izlazni DTO za jedan red {@code transaction_anomalies} tabele.
 *
 * <p>{@code features} ostaje kao raw JSON string — FE ima slobodu da ga
 * prikaze kao formattirani pre-tag ili da parsuje na klijentskoj strani
 * (feature vektor nije fiksiranog schema-a, varira po model_version).
 */
public record FraudAlertDto(
        Long id,
        Long transactionId,
        BigDecimal riskScore,
        String features,
        String modelVersion,
        LocalDateTime computedAt,
        String reviewedBy,
        String reviewStatus,
        LocalDateTime reviewedAt
) {
}
