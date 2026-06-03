package rs.raf.trading.option.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.DebitFundsRequest;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.option.model.Option;
import rs.raf.trading.option.model.OptionType;
import rs.raf.trading.option.repository.OptionRepository;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit testovi {@link OptionService} — copy-first ekstrakcija + money-seam
 * rewiring (faza 2d-C).
 *
 * <p>NAPOMENA: monolitna verzija je menjala {@code Account.balance} bankinog
 * USD racuna preko {@code AccountRepository}. Ovi testovi verifikuju da je
 * money-seam korektno prevezan — exercise novcanu nogu izvodi kroz
 * {@link BankaCoreClient}:
 * <ul>
 *   <li>CALL → {@code debitFunds} sa bankinog USD racuna (banka-core 409 →
 *       {@code IllegalStateException} "Nedovoljno sredstava ...");</li>
 *   <li>PUT → {@code creditFunds} na bankin USD racun.</li>
 * </ul>
 * Autorizacija aktuara ide kroz {@code getUserByEmail} + {@code getUserPermissions}
 * + lokalni {@link ActuaryInfoRepository}.
 */
@ExtendWith(MockitoExtension.class)
class OptionServiceTest {

    @Mock private OptionRepository optionRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private ActuaryInfoRepository actuaryInfoRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private BankaCoreClient bankaCoreClient;
    // W2-T1: options counter (Micrometer) — obavezna dependence u OptionService.
    @Mock private io.micrometer.core.instrument.Counter optionsTotal;

    private OptionService optionService;

    @BeforeEach
    void setUp() {
        optionService = new OptionService(
                optionRepository, listingRepository, actuaryInfoRepository,
                portfolioRepository, bankaCoreClient, optionsTotal);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private InternalUserDto activeEmployee(Long id, String email) {
        return new InternalUserDto(id, "EMPLOYEE", email, "Test", "Employee", true, "Agent");
    }

    private InternalUserDto inactiveEmployee(Long id, String email) {
        return new InternalUserDto(id, "EMPLOYEE", email, "Test", "Employee", false, "Agent");
    }

    private InternalAccountDto bankAccount(BigDecimal balance) {
        return new InternalAccountDto(99L, "333000000000000001", "Banka 2025",
                balance, balance, BigDecimal.ZERO, "USD", "ACTIVE",
                null, null, "BANK_TRADING");
    }

    /** Mock-uje aktuara (employee + ActuaryInfo red) sa AGENT permisijom. */
    private void mockAuthorizedActuary(String email, Long employeeId) {
        when(bankaCoreClient.getUserByEmail(email)).thenReturn(activeEmployee(employeeId, email));
        when(bankaCoreClient.getUserPermissions(email)).thenReturn(List.of("AGENT"));
        when(actuaryInfoRepository.findByEmployeeId(employeeId))
                .thenReturn(Optional.of(new rs.raf.trading.actuary.model.ActuaryInfo()));
    }

    private Option buildOption(Long id,
                               OptionType optionType,
                               BigDecimal currentStockPrice,
                               BigDecimal strikePrice,
                               LocalDate settlementDate,
                               int openInterest) {

        Listing listing = new Listing();
        listing.setId(55L);
        listing.setTicker("AAPL");
        listing.setName("Apple Inc.");
        listing.setListingType(ListingType.STOCK);
        listing.setPrice(currentStockPrice);

        Option option = new Option();
        option.setId(id);
        option.setTicker("AAPL260402C00185000");
        option.setStockListing(listing);
        option.setOptionType(optionType);
        option.setStrikePrice(strikePrice);
        option.setSettlementDate(settlementDate);
        option.setOpenInterest(openInterest);
        option.setContractSize(100);
        return option;
    }

    /** Bankin racun u proizvoljnoj valuti (za ne-USD listing FX-magnitudni test). */
    private InternalAccountDto bankAccountInCurrency(String currencyCode, BigDecimal balance) {
        return new InternalAccountDto(77L, "333000000000000099", "Banka 2025",
                balance, balance, BigDecimal.ZERO, currencyCode, "ACTIVE",
                null, null, "BANK_TRADING");
    }

    /**
     * Opcija na ne-USD listingu (eksplicitan exchangeAcronym → valuta) — koristi se
     * za P1-options-tax-1 (1546/1332) FX-magnitudni test.
     */
    private Option buildOptionOnExchange(OptionType optionType,
                                         String exchangeAcronym,
                                         BigDecimal currentStockPrice,
                                         BigDecimal strikePrice,
                                         int openInterest) {
        Listing listing = new Listing();
        listing.setId(55L);
        listing.setTicker("RAIFRS");
        listing.setName("Raiffeisen banka a.d.");
        listing.setListingType(ListingType.STOCK);
        listing.setExchangeAcronym(exchangeAcronym);
        listing.setPrice(currentStockPrice);

        Option option = new Option();
        option.setId(1L);
        option.setTicker("RAIFRS260402C00005000");
        option.setStockListing(listing);
        option.setOptionType(optionType);
        option.setStrikePrice(strikePrice);
        option.setSettlementDate(LocalDate.now().plusDays(5));
        option.setOpenInterest(openInterest);
        option.setContractSize(100);
        return option;
    }

    // ── exercise authorization ───────────────────────────────────────────────

    @Test
    void exerciseOption_throwsWhenUserIsNotActuaryOrAdmin() {
        when(bankaCoreClient.getUserByEmail("employee@test.com"))
                .thenReturn(activeEmployee(10L, "employee@test.com"));
        when(bankaCoreClient.getUserPermissions("employee@test.com")).thenReturn(List.of("VIEW_STOCKS"));
        when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> optionService.exerciseOption(1L, "employee@test.com"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Samo aktuar");
    }

    @Test
    void exerciseOption_throwsWhenEmployeeIsInactive() {
        when(bankaCoreClient.getUserByEmail("inactive.agent@test.com"))
                .thenReturn(inactiveEmployee(11L, "inactive.agent@test.com"));

        assertThatThrownBy(() -> optionService.exerciseOption(1L, "inactive.agent@test.com"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("aktivan aktuar");
    }

    @Test
    void exerciseOption_throwsWhenUserNotFoundInBankaCore() {
        when(bankaCoreClient.getUserByEmail("ghost@test.com"))
                .thenThrow(new BankaCoreClientException(404, "user not found"));

        assertThatThrownBy(() -> optionService.exerciseOption(1L, "ghost@test.com"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Samo aktuar");
    }

    @Test
    void exerciseOption_throwsWhenOptionMissing() {
        mockAuthorizedActuary("agent@test.com", 12L);
        when(optionRepository.findByIdForUpdate(55L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> optionService.exerciseOption(55L, "agent@test.com"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Option id: 55 not found.");
    }

    @Test
    void exerciseOption_throwsWhenOptionExpired() {
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOption(
                1L, OptionType.CALL,
                new BigDecimal("210.00"), new BigDecimal("180.00"),
                LocalDate.now().minusDays(1), 3);

        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));

        assertThatThrownBy(() -> optionService.exerciseOption(1L, "agent@test.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("istekla");
    }

    @Test
    void exerciseOption_allowedOnExpiryDay_R3_1623() {
        // R3 1623: gate je `settlementDate.isBefore(now)` → exercise je DOZVOLJEN na
        // sam dan isteka (settlement == danas). `cleanupExpiredOptions` brise samo
        // `settlementDate < danas`, pa se opcija NE brise istog dana kad je jos
        // exercisable — granica je usklajena (exercisable do ukljucujuci dan isteka,
        // brisanje tek sledeci dan).
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOption(
                1L, OptionType.CALL,
                new BigDecimal("210.00"), new BigDecimal("180.00"),
                LocalDate.now() /* settlement == DANAS */, 3);
        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));
        when(bankaCoreClient.getBankTradingAccount("USD"))
                .thenReturn(bankAccount(new BigDecimal("100000")));

        optionService.exerciseOption(1L, "agent@test.com");

        // Exercise je prosao (nije bacio "istekla") → openInterest dekrementiran.
        assertThat(option.getOpenInterest()).isEqualTo(2);
    }

    // ── [P2-input-validation-1 / R1 461] money-leg guards (NPE→400) ────────────

    @Test
    void exerciseOption_nullCurrentPrice_throws400NotNpe() {
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOption(
                1L, OptionType.CALL,
                null /* currentPrice nedostupan */, new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 3);
        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));

        assertThatThrownBy(() -> optionService.exerciseOption(1L, "agent@test.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cena");
        // Novcana noga ne sme da se pozove.
        verify(bankaCoreClient, never()).debitFunds(any(), any());
        verify(bankaCoreClient, never()).creditFunds(any(), any());
    }

    @Test
    void exerciseOption_nonPositiveContractSize_throws400() {
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOption(
                1L, OptionType.CALL,
                new BigDecimal("210.00"), new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 3);
        option.setContractSize(0);
        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));

        assertThatThrownBy(() -> optionService.exerciseOption(1L, "agent@test.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contractSize");
        verify(bankaCoreClient, never()).debitFunds(any(), any());
        verify(bankaCoreClient, never()).creditFunds(any(), any());
    }

    @Test
    void exerciseOption_otmCallSucceedsWithWarning() {
        // [BE-STK-02] Spec: kupac opcije ima PRAVO (ne obavezu) da je iskoristi
        // cak i OTM (ekonomski gubitak je njegova odluka). Ranije je test ocekivao
        // hard-throw, sad ocekuje da exercise prodje (sa WARN log-om).
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOption(
                1L, OptionType.CALL,
                new BigDecimal("170.00"), new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 3);

        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));

        // OTM CALL (current 170 < strike 180) — exercise se ne baca, samo loguje warning.
        // Mockujemo bankaCoreClient.getBankTradingAccount da debitFunds prodje.
        when(bankaCoreClient.getBankTradingAccount("USD"))
                .thenReturn(bankAccount(new BigDecimal("100000")));

        optionService.exerciseOption(1L, "agent@test.com");

        // Open interest mora biti dekrementiran — exercise je prosao.
        assertThat(option.getOpenInterest()).isEqualTo(2);
    }

    @Test
    void exerciseOption_throwsWhenOpenInterestIsZero() {
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOption(
                1L, OptionType.PUT,
                new BigDecimal("150.00"), new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 0);

        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));

        assertThatThrownBy(() -> optionService.exerciseOption(1L, "agent@test.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nema otvorenih ugovora");
    }

    // ── exercise money-seam ──────────────────────────────────────────────────

    @Test
    void exerciseOption_decrementsOpenInterestWhenValid() {
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOption(
                1L, OptionType.CALL,
                new BigDecimal("210.00"), new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 4);

        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));
        when(bankaCoreClient.getBankTradingAccount("USD"))
                .thenReturn(bankAccount(new BigDecimal("10000000.00")));
        when(portfolioRepository.findByUserIdAndUserRole(12L, "EMPLOYEE"))
                .thenReturn(java.util.Collections.emptyList());

        optionService.exerciseOption(1L, "agent@test.com");

        verify(optionRepository).save(option);
        assertThat(option.getOpenInterest()).isEqualTo(3);
        // monolit je menjao bankin Account.balance; sad to radi banka-core preko debitFunds.
        verify(bankaCoreClient).debitFunds(any(), any(DebitFundsRequest.class));
        verify(bankaCoreClient, never()).creditFunds(any(), any());
    }

    @Test
    void exerciseOption_callDebitsBankAccountViaBankaCore() {
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOption(
                1L, OptionType.CALL,
                new BigDecimal("210.00"), new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 4);

        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));
        when(bankaCoreClient.getBankTradingAccount("USD"))
                .thenReturn(bankAccount(new BigDecimal("1000000.00")));
        when(portfolioRepository.findByUserIdAndUserRole(12L, "EMPLOYEE"))
                .thenReturn(java.util.Collections.emptyList());

        optionService.exerciseOption(1L, "agent@test.com");

        // strike 180 × contractSize 100 = 18000 USD, idempotency = option-exercise-1-4
        verify(bankaCoreClient).debitFunds(
                eq("option-exercise-1-4"),
                eq(new DebitFundsRequest(99L, new BigDecimal("18000.0000"), BigDecimal.ZERO,
                        "USD", "Izvrsavanje CALL opcije AAPL260402C00185000")));
    }

    @Test
    void exerciseOption_putCreditsBankAccountViaBankaCore() {
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOption(
                1L, OptionType.PUT,
                new BigDecimal("150.00"), new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 3);

        rs.raf.trading.portfolio.model.Portfolio portfolio = new rs.raf.trading.portfolio.model.Portfolio();
        portfolio.setUserId(12L);
        portfolio.setUserRole("EMPLOYEE");
        portfolio.setListingId(55L);
        portfolio.setQuantity(200);
        portfolio.setAverageBuyPrice(new BigDecimal("170.00"));

        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));
        when(bankaCoreClient.getBankTradingAccount("USD"))
                .thenReturn(bankAccount(new BigDecimal("100000.00")));
        when(portfolioRepository.findByUserIdAndUserRole(12L, "EMPLOYEE"))
                .thenReturn(List.of(portfolio));

        optionService.exerciseOption(1L, "agent@test.com");

        // strike 180 × contractSize 100 = 18000 USD, idempotency = option-exercise-1-3
        verify(bankaCoreClient).creditFunds(
                eq("option-exercise-1-3"),
                eq(new CreditFundsRequest(99L, new BigDecimal("18000.0000"), BigDecimal.ZERO,
                        "USD", "Izvrsavanje PUT opcije AAPL260402C00185000")));
        verify(bankaCoreClient, never()).debitFunds(any(), any());
        assertThat(option.getOpenInterest()).isEqualTo(2);
    }

    @Test
    void exerciseOption_usesPessimisticLockQueryNotPlainFindById() {
        // H2: exerciseOption mora da zakljuca Option red (findByIdForUpdate),
        // ne plain findById — inace dva paralelna exercise-a procitaju isti
        // openInterest, dvaput ga dekrementiraju, a idempotency replay-uje drugi
        // settlement (lost-update / off-by-one).
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOption(
                1L, OptionType.CALL,
                new BigDecimal("210.00"), new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 4);

        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));
        when(bankaCoreClient.getBankTradingAccount("USD"))
                .thenReturn(bankAccount(new BigDecimal("10000000.00")));
        when(portfolioRepository.findByUserIdAndUserRole(12L, "EMPLOYEE"))
                .thenReturn(java.util.Collections.emptyList());

        optionService.exerciseOption(1L, "agent@test.com");

        verify(optionRepository).findByIdForUpdate(1L);
        verify(optionRepository, never()).findById(anyLong());
    }

    @Test
    void exerciseOption_callThrowsWhenBankaCoreReturns409() {
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOption(
                1L, OptionType.CALL,
                new BigDecimal("210.00"), new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 4);

        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));
        when(bankaCoreClient.getBankTradingAccount("USD"))
                .thenReturn(bankAccount(new BigDecimal("100.00")));
        // banka-core odbija debit jer bankin racun nema dovoljno sredstava.
        when(bankaCoreClient.debitFunds(any(), any(DebitFundsRequest.class)))
                .thenThrow(new BankaCoreClientException(409, "insufficient funds"));

        assertThatThrownBy(() -> optionService.exerciseOption(1L, "agent@test.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nedovoljno sredstava");
    }

    // ── [W1.11] money-leg-last ordering ──────────────────────────────────────

    @Test
    void exerciseOption_callRunsMoneyLegLast_localMutationsBeforeDebit() {
        // [W1.11] Nepovratna banka-core novcana noga (debitFunds) mora ici POSLEDNJA —
        // posle svih lokalnih JPA mutacija (portfolio save + openInterest save). Tako
        // pad debit-a cini da @Transactional cisto rollback-uje lokalne izmene umesto
        // da novac bude skinut bez isporucenih akcija.
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOption(
                1L, OptionType.CALL,
                new BigDecimal("210.00"), new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 4);

        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));
        when(bankaCoreClient.getBankTradingAccount("USD"))
                .thenReturn(bankAccount(new BigDecimal("10000000.00")));
        when(portfolioRepository.findByUserIdAndUserRole(12L, "EMPLOYEE"))
                .thenReturn(java.util.Collections.emptyList());

        optionService.exerciseOption(1L, "agent@test.com");

        // Obe lokalne mutacije (portfolio save + openInterest save) MORAJU se desiti
        // PRE novcane noge (debitFunds) — to je sustina money-leg-last fixa.
        org.mockito.InOrder inOrder = inOrder(portfolioRepository, optionRepository, bankaCoreClient);
        inOrder.verify(portfolioRepository).save(any(rs.raf.trading.portfolio.model.Portfolio.class));
        inOrder.verify(optionRepository).save(option);
        inOrder.verify(bankaCoreClient).debitFunds(any(), any(DebitFundsRequest.class));
    }

    @Test
    void exerciseOption_putRunsMoneyLegLast_localMutationsBeforeCredit() {
        // [W1.11] PUT analogno: portfolio sell + openInterest save PRE creditFunds.
        // Pre fixa je openInterest save dolazio POSLE credit-a → ako save padne,
        // novac bi bio kreiran (banka kreditovana, akcije vracene).
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOption(
                1L, OptionType.PUT,
                new BigDecimal("150.00"), new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 3);

        rs.raf.trading.portfolio.model.Portfolio portfolio = new rs.raf.trading.portfolio.model.Portfolio();
        portfolio.setUserId(12L);
        portfolio.setUserRole("EMPLOYEE");
        portfolio.setListingId(55L);
        portfolio.setQuantity(200);
        portfolio.setAverageBuyPrice(new BigDecimal("170.00"));

        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));
        when(bankaCoreClient.getBankTradingAccount("USD"))
                .thenReturn(bankAccount(new BigDecimal("100000.00")));
        when(portfolioRepository.findByUserIdAndUserRole(12L, "EMPLOYEE"))
                .thenReturn(List.of(portfolio));

        optionService.exerciseOption(1L, "agent@test.com");

        // openInterest save MORA pre creditFunds (novcana noga POSLEDNJA).
        org.mockito.InOrder inOrder = inOrder(portfolioRepository, optionRepository, bankaCoreClient);
        inOrder.verify(portfolioRepository).save(portfolio);
        inOrder.verify(optionRepository).save(option);
        inOrder.verify(bankaCoreClient).creditFunds(any(), any(CreditFundsRequest.class));
    }

    // ── P1-options-tax-1: FX-magnituda (1546/1332) ───────────────────────────

    @Test
    void exerciseOption_call_nonUsdListing_debitsBankAccountInListingCurrency_notUsd() {
        // P1-options-tax-1 (1546/1332): za BELEX/RSD listing (strike 5000 RSD,
        // contractSize 100 = 500000 RSD) novcana noga MORA ici na RSD bankin racun
        // u RSD iznosu — NE na USD racun (pre fix-a se 500000 RSD slalo na USD racun
        // → ~120× pogresna magnituda, konzervacija razbijena).
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOptionOnExchange(
                OptionType.CALL, "BELEX",
                new BigDecimal("6000.00"), new BigDecimal("5000.00"), 4);

        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));
        when(bankaCoreClient.getBankTradingAccount("RSD"))
                .thenReturn(bankAccountInCurrency("RSD", new BigDecimal("100000000.00")));
        when(portfolioRepository.findByUserIdAndUserRole(12L, "EMPLOYEE"))
                .thenReturn(java.util.Collections.emptyList());

        optionService.exerciseOption(1L, "agent@test.com");

        // Bankin racun se razresava u RSD (valuta listinga), NE u USD.
        verify(bankaCoreClient).getBankTradingAccount("RSD");
        verify(bankaCoreClient, never()).getBankTradingAccount("USD");
        // Debit ide na RSD racun (id 77), iznos 5000 × 100 = 500000 RSD, valuta RSD.
        verify(bankaCoreClient).debitFunds(
                eq("option-exercise-1-4"),
                eq(new DebitFundsRequest(77L, new BigDecimal("500000.0000"), BigDecimal.ZERO,
                        "RSD", "Izvrsavanje CALL opcije RAIFRS260402C00005000")));
    }

    @Test
    void exerciseOption_put_nonUsdListing_creditsBankAccountInListingCurrency() {
        // P1-options-tax-1 (1546/1332): PUT analogno — credit ide na racun u valuti
        // listinga (XETRA → EUR), ne na USD racun.
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOptionOnExchange(
                OptionType.PUT, "XETRA",
                new BigDecimal("80.00"), new BigDecimal("100.00"), 3);

        rs.raf.trading.portfolio.model.Portfolio portfolio = new rs.raf.trading.portfolio.model.Portfolio();
        portfolio.setUserId(12L);
        portfolio.setUserRole("EMPLOYEE");
        portfolio.setListingId(55L);
        portfolio.setQuantity(200);
        portfolio.setAverageBuyPrice(new BigDecimal("90.00"));

        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));
        when(bankaCoreClient.getBankTradingAccount("EUR"))
                .thenReturn(bankAccountInCurrency("EUR", new BigDecimal("1000000.00")));
        when(portfolioRepository.findByUserIdAndUserRole(12L, "EMPLOYEE"))
                .thenReturn(List.of(portfolio));

        optionService.exerciseOption(1L, "agent@test.com");

        verify(bankaCoreClient).getBankTradingAccount("EUR");
        verify(bankaCoreClient, never()).getBankTradingAccount("USD");
        // strike 100 × contractSize 100 = 10000 EUR.
        verify(bankaCoreClient).creditFunds(
                eq("option-exercise-1-3"),
                eq(new CreditFundsRequest(77L, new BigDecimal("10000.0000"), BigDecimal.ZERO,
                        "EUR", "Izvrsavanje PUT opcije RAIFRS260402C00005000")));
    }

    // ── P1-options-tax-1: CALL cost-basis = strikePrice (1547/196) ───────────

    @Test
    void exerciseOption_callCostBasis_usesStrikePriceNotCurrentPrice() {
        // P1-options-tax-1 (1547/196): nove akcije iz CALL exercise-a se upisuju u
        // portfolio sa averageBuyPrice = STRIKE (180), NE currentPrice (210). Pre
        // fix-a je cost-basis bio currentPrice → precenjen → kasniji SELL prikazuje
        // lazno manji realizovani profit (porez potcenjen).
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOption(
                1L, OptionType.CALL,
                new BigDecimal("210.00"), new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 4);

        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));
        when(bankaCoreClient.getBankTradingAccount("USD"))
                .thenReturn(bankAccount(new BigDecimal("10000000.00")));
        when(portfolioRepository.findByUserIdAndUserRole(12L, "EMPLOYEE"))
                .thenReturn(java.util.Collections.emptyList());

        org.mockito.ArgumentCaptor<rs.raf.trading.portfolio.model.Portfolio> captor =
                org.mockito.ArgumentCaptor.forClass(rs.raf.trading.portfolio.model.Portfolio.class);

        optionService.exerciseOption(1L, "agent@test.com");

        verify(portfolioRepository).save(captor.capture());
        rs.raf.trading.portfolio.model.Portfolio saved = captor.getValue();
        // averageBuyPrice = strike 180.00, NE currentPrice 210.00.
        assertThat(saved.getAverageBuyPrice()).isEqualByComparingTo(new BigDecimal("180.00"));
        assertThat(saved.getQuantity()).isEqualTo(100);
    }

    @Test
    void exerciseOption_callCostBasis_blendsStrikeIntoExistingPosition() {
        // Postojeca pozicija (100 @ 100) + CALL exercise (100 @ strike 180) →
        // novi avg = (100×100 + 100×180)/200 = 140, NE (100×100 + 100×210)/200 = 155
        // (sto bi bilo da se koristi currentPrice 210).
        mockAuthorizedActuary("agent@test.com", 12L);

        Option option = buildOption(
                1L, OptionType.CALL,
                new BigDecimal("210.00"), new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 4);

        rs.raf.trading.portfolio.model.Portfolio existing = new rs.raf.trading.portfolio.model.Portfolio();
        existing.setUserId(12L);
        existing.setUserRole("EMPLOYEE");
        existing.setListingId(55L);
        existing.setQuantity(100);
        existing.setAverageBuyPrice(new BigDecimal("100.00"));

        when(optionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(option));
        when(bankaCoreClient.getBankTradingAccount("USD"))
                .thenReturn(bankAccount(new BigDecimal("10000000.00")));
        when(portfolioRepository.findByUserIdAndUserRole(12L, "EMPLOYEE"))
                .thenReturn(List.of(existing));

        optionService.exerciseOption(1L, "agent@test.com");

        assertThat(existing.getQuantity()).isEqualTo(200);
        assertThat(existing.getAverageBuyPrice()).isEqualByComparingTo(new BigDecimal("140.0000"));
    }
}
