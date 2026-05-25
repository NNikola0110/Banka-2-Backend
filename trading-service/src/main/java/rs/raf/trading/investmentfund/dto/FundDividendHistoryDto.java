package rs.raf.trading.investmentfund.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Istorija dividendi koje su pristigle na racun investicionog fonda (B11).
 *
 * <p>Vraca se iz {@code GET /funds/{id}/dividends}. Front-end prikazuje
 * sumu primljenih dividendi po listingu, eventualno reinvestiranih iznosa
 * i raspodelu klijentima. Iznos je u RSD (banka-core fund racun je uvek RSD).</p>
 *
 * <p>Spec: TODO_final C4 #14, Zadaci_Backend.pdf B11.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundDividendHistoryDto {

    /** ID ClientFundTransaction reda. */
    private Long id;

    /** ID fonda. */
    private Long fundId;

    /** ID listinga ciji su prihodi od dividendi (opciono, parsira se iz failureReason marker-a). */
    private Long listingId;

    /** Ticker listinga (npr. "AAPL"). Null ako listing vise ne postoji. */
    private String listingTicker;

    /** Datum knjizenja transakcije (date deo createdAt). */
    private LocalDate paymentDate;

    /** Trenutak knjizenja transakcije (puni timestamp). */
    private LocalDateTime createdAt;

    /** Trenutak finalizacije (reinvest ili distribucija); null ako jos uvek INFLOW. */
    private LocalDateTime completedAt;

    /** Bruto iznos u RSD primljen na racun fonda. */
    private BigDecimal grossAmount;

    /** Status u lifecyclu (DIVIDEND_INFLOW / DIVIDEND_REINVESTED / DIVIDEND_DISTRIBUTED / FAILED). */
    private String status;

    /** Valuta knjizenja — uvek "RSD" (banka-core fund racun). */
    private String currency;

    /** Slobodno polje sa nasleknjenim razlogom (failureReason iz transakcije). */
    private String note;
}
