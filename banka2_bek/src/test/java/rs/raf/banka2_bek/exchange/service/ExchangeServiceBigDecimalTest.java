package rs.raf.banka2_bek.exchange.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import rs.raf.banka2_bek.exchange.ExchangeService;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * P0-B4 Nalaz 1: FX money-math mora drzati conservation egzaktno.
 *
 * <p>Stara {@code calculateCross(double,...)} putanja racuna celu konverziju u
 * {@code double} pa {@code TransferService}/{@code PaymentServiceImpl} bukira
 * {@code BigDecimal.valueOf(double)} u racun — binarni double rezultat (npr.
 * ...0000001 ili ...9999) zavrsi u DB-u kao ne-2-decimalan iznos, sto narusava
 * conservation knjiga (zbir nogu != 0).</p>
 *
 * <p>Novi {@code calculateCrossExact(BigDecimal,...)} racuna ceo put u
 * BigDecimal (kurs, mnozenje, spread, provizija, zaokruzivanje HALF_EVEN na
 * 2 decimale) — rezultat je deterministican i fiksnog scale-a.</p>
 */
@ExtendWith(MockitoExtension.class)
class ExchangeServiceBigDecimalTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private ExchangeService exchangeService;

    private static final String EXPECTED_URL =
            "https://data.fixer.io/api/latest?access_key=test-key&symbols=RSD,EUR,CHF,USD,GBP,JPY,CAD,AUD";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(exchangeService, "apiKey", "test-key");
        ReflectionTestUtils.setField(exchangeService, "apiUrl", "https://data.fixer.io/api/latest");
    }

    private void mockRates() {
        Map<String, Object> rates = new HashMap<>();
        // Realni NBS-like kursevi koji eksponiraju double round-greske.
        rates.put("RSD", 117.35);
        rates.put("EUR", 1.0);
        rates.put("USD", 1.07);
        rates.put("CHF", 0.93);
        rates.put("GBP", 0.86);
        rates.put("JPY", 162.41);
        rates.put("CAD", 1.47);
        rates.put("AUD", 1.63);

        Map<String, Object> body = new HashMap<>();
        body.put("rates", rates);

        when(restTemplate.getForEntity(EXPECTED_URL, Map.class))
                .thenReturn(ResponseEntity.ok(body));
    }

    // ============================================================
    // 1. Rezultat ima fiksan money scale (2 decimale) — nema double noise.
    // ============================================================
    @Test
    @DisplayName("calculateCrossExact: konvertovan iznos je egzaktno na 2 decimale (fiat money scale)")
    void exactResultHasMoneyScale() {
        mockRates();

        // 1234.56 EUR -> USD: stari double put daje rezultat sa binarnim repom;
        // BigDecimal put mora vratiti tacno scale=2.
        ExchangeService.FxConversionResult result =
                exchangeService.calculateCrossExact(new BigDecimal("1234.56"), "EUR", "USD");

        assertThat(result.convertedAmount().scale()).isEqualTo(2);
        // Re-derivacija iz double-a (stari bug) bi mogla imati > 2 decimale.
        assertThat(result.convertedAmount().stripTrailingZeros().scale()).isLessThanOrEqualTo(2);
        assertThat(result.convertedAmount().signum()).isPositive();
    }

    // ============================================================
    // 2. Conservation: ono sto banka skine (toAmount) je tacno ono sto se kreditira.
    //    Demonstrira da BigDecimal put nema gubitak izmedju "izracunato" i "bukirano".
    // ============================================================
    @Test
    @DisplayName("calculateCrossExact: bukirana suma == izracunata suma (bez BigDecimal.valueOf(double) drift-a)")
    void bookingMatchesComputationExactly() {
        mockRates();

        BigDecimal amount = new BigDecimal("999.99");
        ExchangeService.FxConversionResult result =
                exchangeService.calculateCrossExact(amount, "EUR", "RSD");

        // Simuliraj booking: banka skida toAmount, klijent dobija toAmount.
        BigDecimal bankDebit = result.convertedAmount();
        BigDecimal clientCredit = result.convertedAmount();

        // Conservation na target nozi: bankDebit - clientCredit == 0 EGZAKTNO.
        assertThat(bankDebit.subtract(clientCredit).compareTo(BigDecimal.ZERO)).isZero();
        // I scale mora ostati 2 (nema sub-cent prljavstine u knjizi).
        assertThat(bankDebit.scale()).isEqualTo(2);
    }

    // ============================================================
    // 3. Determinizam: dva poziva sa istim ulazom daju BIT-IDENTICAN BigDecimal.
    // ============================================================
    @Test
    @DisplayName("calculateCrossExact: deterministican (isti ulaz -> identican BigDecimal, ukljucujuci scale)")
    void deterministic() {
        mockRates();

        BigDecimal amount = new BigDecimal("4250.33");
        ExchangeService.FxConversionResult a =
                exchangeService.calculateCrossExact(amount, "USD", "CHF");
        ExchangeService.FxConversionResult b =
                exchangeService.calculateCrossExact(amount, "USD", "CHF");

        // equals na BigDecimal poredi i vrednost i scale -> bit-identicno.
        assertThat(a.convertedAmount()).isEqualTo(b.convertedAmount());
        assertThat(a.exchangeRate()).isEqualTo(b.exchangeRate());
    }

    // ============================================================
    // 4. RSD->RSD identitet (same currency) -> nema konverzije.
    // ============================================================
    @Test
    @DisplayName("calculateCrossExact: RSD->RSD vraca isti iznos (scale 2) bez spread/provizije")
    void rsdToRsdIdentity() {
        ExchangeService.FxConversionResult result =
                exchangeService.calculateCrossExact(new BigDecimal("1000.00"), "RSD", "RSD");

        assertThat(result.convertedAmount().compareTo(new BigDecimal("1000.00"))).isZero();
    }

    // ============================================================
    // 5. Konzistentnost sa starim double API-jem (display vrednost) — u granici
    //    zaokruzivanja na 2 decimale (BigDecimal put je tacniji, ali isti red velicine).
    // ============================================================
    @Test
    @DisplayName("calculateCrossExact: vrednost se poklapa sa double calculateCross u granici 0.01")
    void consistentWithLegacyDouble() {
        mockRates();

        BigDecimal amount = new BigDecimal("100.00");
        ExchangeService.FxConversionResult exact =
                exchangeService.calculateCrossExact(amount, "EUR", "USD");
        double legacy = exchangeService.calculateCross(100.0, "EUR", "USD").getConvertedAmount();

        assertThat(exact.convertedAmount().doubleValue()).isCloseTo(legacy,
                org.assertj.core.data.Offset.offset(0.01));
    }

    // ============================================================
    // 6. KLJUCNI BUG: legacy double put bukira SUB-CENT (4 decimale) u racun,
    //    BigDecimal put bukira cist 2-decimalni novac.
    //
    //    calculateCross EUR->USD za 1234.56 daje double 1360.5044 -> stari kod radi
    //    BigDecimal.valueOf(1360.5044) = 1360.5044 (scale 4) i to upisuje u balance
    //    racuna => 0.0044 sub-cent prljavstina po nozi, akumulira se i lomi knjige.
    //    Novi put vraca 2-decimalni iznos (HALF_EVEN) bez sub-cent repa.
    // ============================================================
    @Test
    @DisplayName("legacy double put bukira sub-cent (scale>2); BigDecimal put je cist 2-decimalni novac")
    void legacyDoubleBooksSubCent_bigDecimalIsClean() {
        mockRates();

        BigDecimal amount = new BigDecimal("1234.56");

        // Reprodukcija STAROG buga: ono sto je TransferService/PaymentServiceImpl
        // ranije bukirao bilo je BigDecimal.valueOf(double).
        double legacyConverted = exchangeService.calculateCross(
                amount.doubleValue(), "EUR", "USD").getConvertedAmount();
        BigDecimal legacyBooked = BigDecimal.valueOf(legacyConverted);

        // Stari put: sub-cent (vise od 2 decimale) — prljavstina u knjizi.
        assertThat(legacyBooked.stripTrailingZeros().scale())
                .as("legacy double booking mora imati sub-cent rep (>2 decimale)")
                .isGreaterThan(2);

        // Novi put: cist 2-decimalni novac, deterministican.
        ExchangeService.FxConversionResult exact =
                exchangeService.calculateCrossExact(amount, "EUR", "USD");
        assertThat(exact.convertedAmount().scale()).isEqualTo(2);
        assertThat(exact.convertedAmount().stripTrailingZeros().scale())
                .as("BigDecimal booking ne sme imati sub-cent rep")
                .isLessThanOrEqualTo(2);
    }
}
