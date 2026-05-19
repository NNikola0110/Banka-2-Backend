package rs.raf.trading.dividend.dto;

// ============================================================
// TODO [B9 - Isplata dividendi na akcije | Nosilac: Djordje Zlatanovic]
//
// DTO za odgovor na GET /dividends/my i GET /dividends/by-position/{portfolioId}.
// Mapira se iz DividendPayout entiteta (dodati mapper klasu u
// dividend/mapper/DividendMapper.java ako budete dodavali mapper, ili mapirati
// inline u servisu — po uzoru na trgovinski OptionMapper).
//
// IMPLEMENTIRATI (polja koja klasa treba da ima):
//   - Long id
//   - Long ownerId
//   - String ownerType             — "CLIENT" ili "EMPLOYEE"
//   - Long stockListingId
//   - String stockTicker
//   - Integer quantity
//   - BigDecimal priceOnDate       — cena akcije na dan obracuna
//   - BigDecimal dividendYieldRate — kvartalni prinos (dividendYield / 4)
//   - BigDecimal grossAmount       — bruto iznos pre poreza
//   - BigDecimal tax               — iznos poreza (0 za EMPLOYEE)
//   - BigDecimal netAmount         — neto iznos koji je knjizen na racun
//   - Long creditedAccountId       — racun na koji je isplaceno
//   - String currencyCode          — valuta isplate
//   - LocalDate paymentDate        — datum isplate
//   - Boolean taxExempt            — true za EMPLOYEE (aktuar/bankin racun)
//   - LocalDateTime createdAt
//
// Dodati Lombok anotacije @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
// (po uzoru na trgovinski FundDto).
//
// Konvencija: pratiti trgovinski paket `investmentfund` kao sablon.
// Spec: Zadaci_Backend.pdf, zadatak B9.
// ============================================================

public class DividendPayoutDto {
}
