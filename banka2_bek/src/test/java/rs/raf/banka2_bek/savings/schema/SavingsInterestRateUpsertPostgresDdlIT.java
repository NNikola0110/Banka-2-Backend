package rs.raf.banka2_bek.savings.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.currency.repository.CurrencyRepository;
import rs.raf.banka2_bek.savings.dto.SavingsRateDto;
import rs.raf.banka2_bek.savings.dto.UpsertSavingsRateDto;
import rs.raf.banka2_bek.savings.entity.SavingsInterestRate;
import rs.raf.banka2_bek.savings.repository.SavingsInterestRateRepository;
import rs.raf.banka2_bek.savings.service.SavingsInterestRateService;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TEST-savings-3 (PG-smoke, ✅ FIXED 02.06): uniqueness na {@code savings_interest_rates}
 * je sada PARTIAL UNIQUE INDEX {@code (currency_id, term_months) WHERE active = true}
 * (kreira ga {@link rs.raf.banka2_bek.persistence.SavingsInterestRateIndexInitializer}
 * na startu). Ranije je tu stajao 3-kolonski {@code uk_savings_rates_currency_term_active}
 * unique gde je {@code active} bio PUNI deo kljuca, pa su i {@code false} redovi ucestvovali
 * u jedinstvenosti — sto je obaralo svaku 3. promenu rate-a za isti {@code (currency,term)}.
 *
 * <p>Ovaj test je RANIJE pinovao buggy ponasanje (3. upsert puca sa
 * {@link DataIntegrityViolationException}); sada asertuje KOREKTNO ponasanje:
 * <ul>
 *   <li>3. (i N-ta) promena rate-a za isti {@code (currency,term)} USPEVA — istorijski
 *       {@code false} redovi se gomilaju bez sudara;</li>
 *   <li>najvise JEDAN aktivan red po {@code (currency,term)} (dva aktivna i dalje
 *       sudaraju na partial indeksu).</li>
 * </ul>
 *
 * <p>{@code @EnabledIf("dockerAvailable")}: gracefully skip bez Docker-a (isti obrazac
 * kao {@code BankaCoreSchemaPostgresDdlTest}); u CI radi protiv pravog postgres kontejnera
 * gde se partial index zaista kreira (H2 ne reprodukuje partial-unique semantiku).
 */
@Testcontainers
@EnabledIf("dockerAvailable")
@SpringBootTest
@ActiveProfiles("test")
class SavingsInterestRateUpsertPostgresDdlIT {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired private SavingsInterestRateRepository rateRepo;
    @Autowired private CurrencyRepository currencyRepo;
    @Autowired private SavingsInterestRateService rateService;

    private Currency ensureCurrency(String code) {
        return currencyRepo.findByCode(code).orElseGet(() -> {
            Currency c = new Currency();
            c.setCode(code);
            c.setName(code);
            c.setSymbol(code);
            c.setCountry("RS");
            c.setDescription("test");
            c.setActive(true);
            return currencyRepo.save(c);
        });
    }

    private SavingsInterestRate rate(Currency currency, int term, String annual, boolean active) {
        return SavingsInterestRate.builder()
                .currency(currency)
                .termMonths(term)
                .annualRate(new BigDecimal(annual))
                .active(active)
                .effectiveFrom(LocalDate.now())
                .build();
    }

    private UpsertSavingsRateDto upsertDto(String currency, int term, String annual) {
        return new UpsertSavingsRateDto(currency, term, new BigDecimal(annual));
    }

    @Test
    void firstActiveRate_persists() {
        Currency rsd = ensureCurrency("RSD");
        SavingsInterestRate saved = rateRepo.saveAndFlush(rate(rsd, 12, "4.00", true));
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void deactivateThenNewActive_secondUpsertOk_oneInactiveRow() {
        Currency rsd = ensureCurrency("RSD");
        // 1. upsert: aktivan red.
        SavingsInterestRate first = rateRepo.saveAndFlush(rate(rsd, 24, "4.00", true));
        // 2. upsert: deaktiviraj prvi ((RSD,24,false)) + nov aktivan.
        first.setActive(false);
        rateRepo.saveAndFlush(first);
        SavingsInterestRate second = rateRepo.saveAndFlush(rate(rsd, 24, "4.50", true));
        assertThat(second.getId()).isNotNull();
        assertThat(rateRepo.findActive(rsd.getId(), 24)).isPresent();
    }

    @Test
    void thirdAndNthUpsert_succeed_partialUniqueOnActive_TEST_savings_3() {
        Currency rsd = ensureCurrency("RSD");
        // Vise SUKCESIVNIH deaktivacija + insert-a aktivnog reda za isti (currency,term)
        // raniji insert vise NE sudara — vise (RSD,36,false) redova je dozvoljeno.
        SavingsInterestRate r1 = rateRepo.saveAndFlush(rate(rsd, 36, "4.00", true));

        r1.setActive(false);
        rateRepo.saveAndFlush(r1);
        SavingsInterestRate r2 = rateRepo.saveAndFlush(rate(rsd, 36, "4.50", true));

        // 3. promena: deaktivacija r2 pravi DRUGI (RSD,36,false) red — RANIJE bi pucalo,
        // sada uspeva (partial-unique je samo na active=true).
        r2.setActive(false);
        SavingsInterestRate r2deactivated = rateRepo.saveAndFlush(r2);
        SavingsInterestRate r3 = rateRepo.saveAndFlush(rate(rsd, 36, "5.00", true));

        assertThat(r2deactivated.getId()).isNotNull();
        assertThat(r3.getId()).isNotNull();
        // 4. i 5. promena takodje prolaze (N-ta promena nije ogranicena).
        r3.setActive(false);
        rateRepo.saveAndFlush(r3);
        SavingsInterestRate r4 = rateRepo.saveAndFlush(rate(rsd, 36, "5.50", true));
        assertThat(r4.getId()).isNotNull();

        // Tacno JEDAN aktivan red za (RSD,36) — onaj poslednji.
        assertThat(rateRepo.findActive(rsd.getId(), 36))
                .isPresent()
                .get()
                .extracting(SavingsInterestRate::getAnnualRate)
                .isEqualTo(new BigDecimal("5.50"));
    }

    @Test
    void appLevelUpsertOnce_repeatedRateChanges_succeed_TEST_savings_3() {
        // Pravi app-level put: SavingsInterestRateService.upsert (kroz @Retryable
        // SavingsInterestRateUpserter) protiv pravog PG-a — vise od JEDNE promene
        // rate-a za isti (currency,term) mora da prodje (RANIJE je 3. pucala u 500).
        ensureCurrency("EUR");

        SavingsRateDto first = rateService.upsert(upsertDto("EUR", 12, "2.00"));
        SavingsRateDto second = rateService.upsert(upsertDto("EUR", 12, "2.25"));
        SavingsRateDto third = rateService.upsert(upsertDto("EUR", 12, "2.50"));
        SavingsRateDto fourth = rateService.upsert(upsertDto("EUR", 12, "2.75"));

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(third).isNotNull();
        assertThat(fourth).isNotNull();

        // Aktivan rate je poslednji upsert; tacno jedan aktivan red.
        Currency eur = currencyRepo.findByCode("EUR").orElseThrow();
        assertThat(rateRepo.findActive(eur.getId(), 12))
                .isPresent()
                .get()
                .extracting(SavingsInterestRate::getAnnualRate)
                .isEqualTo(new BigDecimal("2.75"));
    }

    @Test
    void twoActiveRowsForSamePairStillCollide_partialUnique_TEST_savings_3() {
        Currency rsd = ensureCurrency("RSD");
        // Partial-unique i dalje garantuje NAJVISE JEDAN aktivan red po (currency,term):
        // drugi AKTIVAN red za isti par se odmah sudara.
        rateRepo.saveAndFlush(rate(rsd, 6, "2.00", true));
        assertThatThrownBy(() -> rateRepo.saveAndFlush(rate(rsd, 6, "2.50", true)))
                .as("dva aktivna reda za isti (currency,term) sudaraju na "
                        + "uk_savings_rates_active (partial-unique WHERE active=true)")
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
