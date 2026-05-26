package rs.raf.trading.prediction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * [W3-T2] Izlazni DTO za jednu price predikciju.
 *
 * <p>FE renderuje confidence interval ({@code lowerBound}..{@code upperBound})
 * kao traku oko {@code predictedClose}. {@code modelVersion} se prikazuje
 * malim fontom ("Forecast by rf_v1") radi transparentnosti.
 */
public record PricePredictionDto(
        Long id,
        String symbol,
        LocalDate predictionDate,
        BigDecimal predictedClose,
        BigDecimal lowerBound,
        BigDecimal upperBound,
        String modelVersion,
        LocalDateTime computedAt
) {
}
