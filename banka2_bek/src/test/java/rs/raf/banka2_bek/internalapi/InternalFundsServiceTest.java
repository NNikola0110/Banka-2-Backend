package rs.raf.banka2_bek.internalapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2.contracts.internal.CommitFundsRequest;
import rs.raf.banka2.contracts.internal.CommitFundsResponse;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.CreditFundsResponse;
import rs.raf.banka2.contracts.internal.DebitFundsRequest;
import rs.raf.banka2.contracts.internal.DebitFundsResponse;
import rs.raf.banka2.contracts.internal.ReleaseFundsRequest;
import rs.raf.banka2.contracts.internal.ReleaseFundsResponse;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;
import rs.raf.banka2.contracts.internal.TaxCollectRequest;
import rs.raf.banka2.contracts.internal.TaxCollectResponse;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.banka2.contracts.internal.TransferFundsResponse;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountCategory;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.model.AccountType;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.exchange.CurrencyConversionService;
import rs.raf.banka2_bek.internalapi.model.FundReservation;
import rs.raf.banka2_bek.internalapi.model.FundReservationStatus;
import rs.raf.banka2_bek.internalapi.model.InternalRequest;
import rs.raf.banka2_bek.internalapi.repository.FundReservationRepository;
import rs.raf.banka2_bek.internalapi.service.InternalFundsService;
import rs.raf.banka2_bek.internalapi.service.InternalIdempotencyService;
import rs.raf.banka2_bek.transaction.repository.TransactionRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class InternalFundsServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock FundReservationRepository fundReservationRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock InternalIdempotencyService idempotencyService;
    @Mock ObjectMapper objectMapper;
    @Mock CurrencyConversionService currencyConversionService;

    @InjectMocks InternalFundsService service;

    private Currency rsd;
    private Currency eur;
    private Account account;
    /** Bankin BANK_TRADING racun u RSD — prima provizije. */
    private Account bankTradingAccount;

    @BeforeEach
    void setUp() {
        // state.registration-number — InternalFundsService ga cita @Value-om
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "stateRegistrationNumber", "17858459");

        rsd = new Currency();
        rsd.setId(1L);
        rsd.setCode("RSD");
        rsd.setName("Srpski dinar");
        rsd.setSymbol("din");
        rsd.setCountry("RS");

        eur = new Currency();
        eur.setId(2L);
        eur.setCode("EUR");
        eur.setName("Evro");
        eur.setSymbol("€");
        eur.setCountry("EU");

        account = Account.builder()
                .accountNumber("222000100000000001")
                .accountType(AccountType.CHECKING)
                .currency(rsd)
                .balance(new BigDecimal("10000.00"))
                .availableBalance(new BigDecimal("10000.00"))
                .reservedAmount(BigDecimal.ZERO)
                .dailyLimit(new BigDecimal("50000.00"))
                .monthlyLimit(new BigDecimal("200000.00"))
                .dailySpending(BigDecimal.ZERO)
                .monthlySpending(BigDecimal.ZERO)
                .build();

        // Set id via reflection since @Builder doesn't include id
        org.springframework.test.util.ReflectionTestUtils.setField(account, "id", 1L);

        bankTradingAccount = Account.builder()
                .accountNumber("222000900000000099")
                .accountType(AccountType.CHECKING)
                .accountCategory(AccountCategory.BANK_TRADING)
                .currency(rsd)
                .balance(new BigDecimal("100000.00"))
                .availableBalance(new BigDecimal("100000.00"))
                .reservedAmount(BigDecimal.ZERO)
                .dailyLimit(BigDecimal.ZERO)
                .monthlyLimit(BigDecimal.ZERO)
                .dailySpending(BigDecimal.ZERO)
                .monthlySpending(BigDecimal.ZERO)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(bankTradingAccount, "id", 99L);
    }

    // ─── reserve tests ────────────────────────────────────────────────────────

    @Test
    void reserve_happyPath_decreasesAvailableAndIncreasesReserved() {
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(fundReservationRepository.save(any(FundReservation.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        ReserveFundsRequest req = new ReserveFundsRequest(1L, new BigDecimal("500.00"), "RSD");
        ReserveFundsResponse response = service.reserve(req);

        assertThat(response).isNotNull();
        assertThat(response.reservationId()).isNotBlank();
        assertThat(response.accountId()).isEqualTo(1L);
        assertThat(response.reservedAmount()).isEqualByComparingTo("500.00");
        assertThat(response.availableBalanceAfter()).isEqualByComparingTo("9500.00");
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("9500.00");
        assertThat(account.getReservedAmount()).isEqualByComparingTo("500.00");

        ArgumentCaptor<FundReservation> captor = ArgumentCaptor.forClass(FundReservation.class);
        verify(fundReservationRepository).save(captor.capture());
        FundReservation saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(FundReservationStatus.RESERVED);
        assertThat(saved.getAmount()).isEqualByComparingTo("500.00");
        assertThat(saved.getReservationId()).isNotBlank();
        verify(accountRepository).save(account);
    }

    @Test
    void reserve_insufficientFunds_throwsIllegalStateException() {
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));

        ReserveFundsRequest req = new ReserveFundsRequest(1L, new BigDecimal("99999.00"), "RSD");
        assertThatThrownBy(() -> service.reserve(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nedovoljno raspolozivih sredstava");

        verify(fundReservationRepository, never()).save(any());
    }

    @Test
    void reserve_wrongCurrency_throwsIllegalArgumentException() {
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));

        ReserveFundsRequest req = new ReserveFundsRequest(1L, new BigDecimal("100.00"), "EUR");
        assertThatThrownBy(() -> service.reserve(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Valuta");

        verify(fundReservationRepository, never()).save(any());
    }

    @Test
    void reserve_nonExistentAccount_throwsIllegalArgumentException() {
        when(accountRepository.findForUpdateById(99L)).thenReturn(Optional.empty());

        ReserveFundsRequest req = new ReserveFundsRequest(99L, new BigDecimal("100.00"), "RSD");
        assertThatThrownBy(() -> service.reserve(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Racun ne postoji");
    }

    // ─── commit tests ─────────────────────────────────────────────────────────

    @Test
    void commit_happyPath_noCommission_decreasesBalanceAndReserved() {
        // reserve() would have set reservedAmount=1000 before commit is called
        account.setReservedAmount(new BigDecimal("1000.00"));
        FundReservation reservation = buildReservation("res-001", 1L, "1000.00", "0.00", FundReservationStatus.RESERVED);

        when(fundReservationRepository.findByReservationIdForUpdate("res-001"))
                .thenReturn(Optional.of(reservation));
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fundReservationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CommitFundsRequest req = new CommitFundsRequest(new BigDecimal("800.00"), BigDecimal.ZERO, null, "BUY fill");
        CommitFundsResponse response = service.commit("res-001", req);

        assertThat(response).isNotNull();
        assertThat(response.reservationId()).isEqualTo("res-001");
        assertThat(response.committedTotal()).isEqualByComparingTo("800.00");
        assertThat(account.getBalance()).isEqualByComparingTo("9200.00");
        // reservedAmount started at 1000, subtract settle(800) → 200
        assertThat(account.getReservedAmount()).isEqualByComparingTo("200.00");
        // commission == 0 → bankin racun se NE dira
        verify(accountRepository, never())
                .findByAccountCategoryAndCurrencyCodeForUpdate(any(), anyString());
    }

    @Test
    void commit_withCommission_creditsCommissionToBankTradingAccount() {
        // reserve() set reservedAmount=1000; fill kosta 800 + 50 provizije
        account.setReservedAmount(new BigDecimal("1000.00"));
        FundReservation reservation = buildReservation("res-001c", 1L, "1000.00", "0.00", FundReservationStatus.RESERVED);

        when(fundReservationRepository.findByReservationIdForUpdate("res-001c"))
                .thenReturn(Optional.of(reservation));
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        // P0-B3: provizija se sad kreditira preko PESSIMISTIC_WRITE lookup-a (lost-update fix).
        when(accountRepository.findByAccountCategoryAndCurrencyCodeForUpdate(
                AccountCategory.BANK_TRADING, "RSD")).thenReturn(List.of(bankTradingAccount));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fundReservationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CommitFundsRequest req = new CommitFundsRequest(
                new BigDecimal("800.00"), new BigDecimal("50.00"), null, "BUY fill");
        CommitFundsResponse response = service.commit("res-001c", req);

        // settle = 800 + 50 = 850 → balance 10000-850 = 9150, reserved 1000-850 = 150
        assertThat(response.committedTotal()).isEqualByComparingTo("850.00");
        assertThat(account.getBalance()).isEqualByComparingTo("9150.00");
        assertThat(account.getReservedAmount()).isEqualByComparingTo("150.00");
        // Provizija (50) je kreditovana bankinom BANK_TRADING racunu
        assertThat(bankTradingAccount.getBalance()).isEqualByComparingTo("100050.00");
        assertThat(bankTradingAccount.getAvailableBalance()).isEqualByComparingTo("100050.00");
        verify(accountRepository).save(bankTradingAccount);
        // P0-B3 (lost-update fix): bankin provizioni racun MORA biti razresen pod
        // PESSIMISTIC_WRITE lock-om, NE preko ne-locking findFirstBy...Currency_Code.
        verify(accountRepository).findByAccountCategoryAndCurrencyCodeForUpdate(
                AccountCategory.BANK_TRADING, "RSD");
        verify(accountRepository, never())
                .findFirstByAccountCategoryAndCurrency_Code(any(), anyString());
    }

    @Test
    void commit_withBeneficiary_creditsBeneficiaryAccount() {
        // reserve() would have set reservedAmount=500 before commit is called
        account.setReservedAmount(new BigDecimal("500.00"));
        FundReservation reservation = buildReservation("res-002", 1L, "500.00", "0.00", FundReservationStatus.RESERVED);

        Account beneficiary = Account.builder()
                .accountNumber("222000200000000002")
                .accountType(AccountType.CHECKING)
                .currency(rsd)
                .balance(new BigDecimal("1000.00"))
                .availableBalance(new BigDecimal("1000.00"))
                .reservedAmount(BigDecimal.ZERO)
                .dailyLimit(BigDecimal.ZERO)
                .monthlyLimit(BigDecimal.ZERO)
                .dailySpending(BigDecimal.ZERO)
                .monthlySpending(BigDecimal.ZERO)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(beneficiary, "id", 2L);

        when(fundReservationRepository.findByReservationIdForUpdate("res-002"))
                .thenReturn(Optional.of(reservation));
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findForUpdateById(2L)).thenReturn(Optional.of(beneficiary));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fundReservationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CommitFundsRequest req = new CommitFundsRequest(new BigDecimal("500.00"), BigDecimal.ZERO, 2L, "OTC premium");
        CommitFundsResponse resp = service.commit("res-002", req);

        // Response assertions: settle=500+0=500; account.balance=10000-500=9500
        assertThat(resp.committedTotal()).isEqualByComparingTo("500.00");
        assertThat(resp.balanceAfter()).isEqualByComparingTo("9500.00");
        // Beneficiary credited with amount (not amount+commission)
        assertThat(beneficiary.getBalance()).isEqualByComparingTo("1500.00");
        assertThat(beneficiary.getAvailableBalance()).isEqualByComparingTo("1500.00");
    }

    @Test
    void commit_overReservation_throwsIllegalStateException() {
        FundReservation reservation = buildReservation("res-003", 1L, "100.00", "0.00", FundReservationStatus.RESERVED);

        when(fundReservationRepository.findByReservationIdForUpdate("res-003"))
                .thenReturn(Optional.of(reservation));

        CommitFundsRequest req = new CommitFundsRequest(new BigDecimal("999.00"), BigDecimal.ZERO, null, "too much");
        assertThatThrownBy(() -> service.commit("res-003", req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("premasuje");
    }

    @Test
    void commit_inactiveReservation_throwsIllegalStateException() {
        FundReservation reservation = buildReservation("res-004", 1L, "100.00", "100.00", FundReservationStatus.COMMITTED);

        when(fundReservationRepository.findByReservationIdForUpdate("res-004"))
                .thenReturn(Optional.of(reservation));

        CommitFundsRequest req = new CommitFundsRequest(new BigDecimal("50.00"), BigDecimal.ZERO, null, "test");
        assertThatThrownBy(() -> service.commit("res-004", req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Rezervacija nije aktivna");
    }

    // ─── release tests ────────────────────────────────────────────────────────

    @Test
    void release_happyPath_returnsRemainingToAvailable() {
        // Account starts with availableBalance=8000, reservedAmount=2000 (1000 already committed)
        account.setAvailableBalance(new BigDecimal("8000.00"));
        account.setReservedAmount(new BigDecimal("2000.00"));
        FundReservation reservation = buildReservation("res-005", 1L, "2000.00", "1000.00", FundReservationStatus.RESERVED);

        when(fundReservationRepository.findByReservationIdForUpdate("res-005"))
                .thenReturn(Optional.of(reservation));
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fundReservationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReleaseFundsRequest req = new ReleaseFundsRequest("SAGA rollback");
        ReleaseFundsResponse response = service.release("res-005", req);

        assertThat(response.reservationId()).isEqualTo("res-005");
        assertThat(response.releasedAmount()).isEqualByComparingTo("1000.00");
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("9000.00");
        assertThat(account.getReservedAmount()).isEqualByComparingTo("1000.00");
        assertThat(reservation.getStatus()).isEqualTo(FundReservationStatus.RELEASED);
    }

    @Test
    void release_alreadyReleased_isIdempotentNoOp() {
        FundReservation reservation = buildReservation("res-006", 1L, "500.00", "0.00", FundReservationStatus.RELEASED);

        when(fundReservationRepository.findByReservationIdForUpdate("res-006"))
                .thenReturn(Optional.of(reservation));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        ReleaseFundsRequest req = new ReleaseFundsRequest("duplicate release");
        ReleaseFundsResponse response = service.release("res-006", req);

        // Should return zero released amount (no-op)
        assertThat(response.releasedAmount()).isEqualByComparingTo("0.00");
        // reservationId echoed back correctly
        assertThat(response.reservationId()).isEqualTo("res-006");
        // availableBalanceAfter non-null and matches current account available balance (read-only lookup)
        assertThat(response.availableBalanceAfter()).isNotNull();
        assertThat(response.availableBalanceAfter()).isEqualByComparingTo(account.getAvailableBalance());
        // Account mutations NOT called
        verify(accountRepository, never()).findForUpdateById(any());
        verify(accountRepository, never()).save(any());
        verify(fundReservationRepository, never()).save(any());
    }

    // ─── transfer tests ───────────────────────────────────────────────────────

    @Test
    void transfer_happyPath_noCommission_debitsFromCreditTo() {
        Account toAccount = buildPlainAccount("222000200000000003", "500.00", 2L);

        // transfer locks in min-id order: 1 then 2
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findForUpdateById(2L)).thenReturn(Optional.of(toAccount));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // ista valuta → debitAmount == creditAmount == 200
        TransferFundsRequest req = new TransferFundsRequest(
                1L, new BigDecimal("200.00"), 2L, new BigDecimal("200.00"),
                null, null, "dividend");
        TransferFundsResponse response = service.transfer(req);

        assertThat(response.fromAccountId()).isEqualTo(1L);
        assertThat(response.toAccountId()).isEqualTo(2L);
        assertThat(response.amount()).isEqualByComparingTo("200.00");
        assertThat(account.getBalance()).isEqualByComparingTo("9800.00");
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("9800.00");
        assertThat(toAccount.getBalance()).isEqualByComparingTo("700.00");
        assertThat(toAccount.getAvailableBalance()).isEqualByComparingTo("700.00");
        // commission == null → bankin racun se NE dira
        verify(accountRepository, never())
                .findByAccountCategoryAndCurrencyCodeForUpdate(any(), anyString());
    }

    @Test
    void transfer_withCommission_debitsDebitAmountAndCreditsBank() {
        Account toAccount = buildPlainAccount("222000200000000003", "500.00", 2L);

        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findForUpdateById(2L)).thenReturn(Optional.of(toAccount));
        when(accountRepository.findByAccountCategoryAndCurrencyCodeForUpdate(
                AccountCategory.BANK_TRADING, "RSD")).thenReturn(List.of(bankTradingAccount));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Pozivalac je vec uradio FX matematiku: from gubi debitAmount 215 (svoja
        // valuta), to dobija creditAmount 200, banka dobija proviziju 15.
        TransferFundsRequest req = new TransferFundsRequest(
                1L, new BigDecimal("215.00"), 2L, new BigDecimal("200.00"),
                new BigDecimal("15.00"), "RSD", "fond uplata");
        TransferFundsResponse response = service.transfer(req);

        // response.amount() echo-uje debitAmount (from-noga)
        assertThat(response.amount()).isEqualByComparingTo("215.00");
        // from skida debitAmount = 215 → 10000-215 = 9785
        assertThat(account.getBalance()).isEqualByComparingTo("9785.00");
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("9785.00");
        // to dobija creditAmount (200)
        assertThat(toAccount.getBalance()).isEqualByComparingTo("700.00");
        // banka dobija proviziju (15)
        assertThat(bankTradingAccount.getBalance()).isEqualByComparingTo("100015.00");
        assertThat(bankTradingAccount.getAvailableBalance()).isEqualByComparingTo("100015.00");
        verify(accountRepository).save(bankTradingAccount);
    }

    @Test
    void transfer_crossCurrency_debitsFromInOwnCurrencyAndCreditsToInOwnCurrency() {
        // from = EUR racun (1015 EUR), to = RSD racun (0 RSD). Pozivalac je vec
        // uradio FX: from gubi debitAmount 1015 EUR, to dobija creditAmount
        // 119000 RSD, banka dobija proviziju 15 EUR.
        Account eurFrom = buildPlainAccount("311000100000000001", "5000.00", 1L, eur);
        Account rsdTo = buildPlainAccount("222000200000000005", "0.00", 2L);

        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(eurFrom));
        when(accountRepository.findForUpdateById(2L)).thenReturn(Optional.of(rsdTo));
        when(accountRepository.findByAccountCategoryAndCurrencyCodeForUpdate(
                AccountCategory.BANK_TRADING, "EUR")).thenReturn(List.of(
                buildPlainAccount("311000900000000099", "100000.00", 99L, eur)));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransferFundsRequest req = new TransferFundsRequest(
                1L, new BigDecimal("1015.00"), 2L, new BigDecimal("119000.00"),
                new BigDecimal("15.00"), "EUR", "Uplata u fond — transakcija #77");
        TransferFundsResponse response = service.transfer(req);

        assertThat(response.fromAccountId()).isEqualTo(1L);
        assertThat(response.toAccountId()).isEqualTo(2L);
        // from-noga: EUR racun gubi 1015 EUR (5000 - 1015 = 3985)
        assertThat(eurFrom.getBalance()).isEqualByComparingTo("3985.00");
        assertThat(eurFrom.getAvailableBalance()).isEqualByComparingTo("3985.00");
        // to-noga: RSD racun dobija 119000 RSD
        assertThat(rsdTo.getBalance()).isEqualByComparingTo("119000.00");
        assertThat(rsdTo.getAvailableBalance()).isEqualByComparingTo("119000.00");
        // banka dobija proviziju u EUR — bankin EUR racun resolve-ovan (PESSIMISTIC_WRITE)
        verify(accountRepository).findByAccountCategoryAndCurrencyCodeForUpdate(
                AccountCategory.BANK_TRADING, "EUR");
    }

    @Test
    void transfer_insufficientFunds_throwsIllegalStateException() {
        Account toAccount = buildPlainAccount("222000200000000004", "0.00", 2L);

        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findForUpdateById(2L)).thenReturn(Optional.of(toAccount));

        TransferFundsRequest req = new TransferFundsRequest(
                1L, new BigDecimal("99999.00"), 2L, new BigDecimal("99999.00"),
                null, null, "test");
        assertThatThrownBy(() -> service.transfer(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nedovoljno raspolozivih sredstava");

        verify(accountRepository, never()).save(any());
    }

    // ─── TEST-branches-3: transfer FX integrity check (BE-INT-07) + provizija edge ──
    // Validacije se dese PRE pesimistickog lock-a, pa ne treba stub-ovati racune.

    @Test
    void transfer_negativeCommission_rejected_TEST_branches_3() {
        TransferFundsRequest req = new TransferFundsRequest(
                1L, new BigDecimal("100.00"), 2L, new BigDecimal("100.00"),
                new BigDecimal("-5.00"), "RSD", "test");

        assertThatThrownBy(() -> service.transfer(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Provizija ne sme biti negativna");

        verify(accountRepository, never()).findForUpdateById(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void transfer_expectedRateNonPositive_rejected_TEST_branches_3() {
        TransferFundsRequest req = new TransferFundsRequest(
                1L, new BigDecimal("100.00"), 2L, new BigDecimal("100.00"),
                null, null, "test", BigDecimal.ZERO); // expectedRate == 0

        assertThatThrownBy(() -> service.transfer(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedRate mora biti pozitivan");

        verify(accountRepository, never()).findForUpdateById(any());
    }

    @Test
    void transfer_fxDriftExceeds1Percent_rejected_TEST_branches_3() {
        // debitAmount=100, expectedRate=2.0 → computedCredit=200. Stvarni creditAmount=100
        // → drift=100 >> 1% od 100 (=1) → odbijeno (inverzni kurs / off-by-decimal bug).
        TransferFundsRequest req = new TransferFundsRequest(
                1L, new BigDecimal("100.00"), 2L, new BigDecimal("100.00"),
                null, null, "cross-currency", new BigDecimal("2.0"));

        assertThatThrownBy(() -> service.transfer(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FX rate drift exceeds 1% tolerance");

        verify(accountRepository, never()).findForUpdateById(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void transfer_fxDriftWithinTolerance_accepted_TEST_branches_3() {
        // debitAmount=100, expectedRate=1.005 → computedCredit=100.5. creditAmount=100
        // → drift=0.5 <= 1% od 100 (=1) → prolazi.
        Account toAccount = buildPlainAccount("222000200000000009", "0.00", 2L);
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findForUpdateById(2L)).thenReturn(Optional.of(toAccount));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransferFundsRequest req = new TransferFundsRequest(
                1L, new BigDecimal("100.00"), 2L, new BigDecimal("100.00"),
                null, null, "within-tolerance", new BigDecimal("1.005"));

        TransferFundsResponse response = service.transfer(req);

        assertThat(response.fromAccountId()).isEqualTo(1L);
        assertThat(account.getBalance()).isEqualByComparingTo("9900.00");
        assertThat(toAccount.getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void transfer_expectedRateNull_skipsFxCheck_TEST_branches_3() {
        // expectedRate==null → FX integrity check se preskace (back-compat), prenos prolazi
        // i kad bi racunski "drift" bio veliki (caller je odgovoran).
        Account toAccount = buildPlainAccount("222000200000000010", "0.00", 2L);
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findForUpdateById(2L)).thenReturn(Optional.of(toAccount));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // debitAmount 100, creditAmount 5000 (cross-currency) — bez expectedRate provere.
        TransferFundsRequest req = new TransferFundsRequest(
                1L, new BigDecimal("100.00"), 2L, new BigDecimal("5000.00"),
                null, null, "no-expectedRate"); // 7-arg ctor → expectedRate=null

        TransferFundsResponse response = service.transfer(req);

        assertThat(response.fromAccountId()).isEqualTo(1L);
        assertThat(toAccount.getBalance()).isEqualByComparingTo("5000.00");
    }

    // ─── idempotency tests ────────────────────────────────────────────────────

    @Test
    void reserveIdempotent_cachedKey_returnsCachedResponseWithoutReExecuting() throws Exception {
        String cachedJson = "{\"reservationId\":\"cached-res\",\"accountId\":1,"
                + "\"reservedAmount\":100.00,\"availableBalanceAfter\":9900.00}";
        InternalRequest cachedRequest = new InternalRequest();
        cachedRequest.setIdempotencyKey("idem-key-1");
        cachedRequest.setEndpoint("/internal/funds/reserve");
        cachedRequest.setHttpStatus(200);
        cachedRequest.setResponseBody(cachedJson);

        when(idempotencyService.findCached("idem-key-1")).thenReturn(Optional.of(cachedRequest));

        ReserveFundsResponse expectedResponse = new ReserveFundsResponse(
                "cached-res", 1L, new BigDecimal("100.00"), new BigDecimal("9900.00"));
        when(objectMapper.readValue(cachedJson, ReserveFundsResponse.class)).thenReturn(expectedResponse);

        ReserveFundsRequest req = new ReserveFundsRequest(1L, new BigDecimal("100.00"), "RSD");
        ReserveFundsResponse response = service.reserveIdempotent("idem-key-1", req);

        assertThat(response.reservationId()).isEqualTo("cached-res");

        // Core mutating repository calls MUST NOT happen on cached path
        verify(accountRepository, never()).findForUpdateById(any());
        verify(fundReservationRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    // ─── credit tests ─────────────────────────────────────────────────────────

    @Test
    void credit_noCommission_creditsAccountOneSided() {
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreditFundsRequest req = new CreditFundsRequest(
                1L, new BigDecimal("750.00"), BigDecimal.ZERO, "RSD", "SELL prihod");
        CreditFundsResponse response = service.credit(req);

        assertThat(response.accountId()).isEqualTo(1L);
        assertThat(response.creditedAmount()).isEqualByComparingTo("750.00");
        // balance 10000 + 750 = 10750 — bez ijednog debit-a (trziste je apstraktan izvor)
        assertThat(response.balanceAfter()).isEqualByComparingTo("10750.00");
        assertThat(account.getBalance()).isEqualByComparingTo("10750.00");
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("10750.00");
        verify(accountRepository, never())
                .findByAccountCategoryAndCurrencyCodeForUpdate(any(), anyString());
    }

    @Test
    void credit_withCommission_creditsAccountAndBank() {
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findByAccountCategoryAndCurrencyCodeForUpdate(
                AccountCategory.BANK_TRADING, "RSD")).thenReturn(List.of(bankTradingAccount));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreditFundsRequest req = new CreditFundsRequest(
                1L, new BigDecimal("1000.00"), new BigDecimal("25.00"), "RSD", "SELL prihod");
        CreditFundsResponse response = service.credit(req);

        assertThat(response.creditedAmount()).isEqualByComparingTo("1000.00");
        assertThat(account.getBalance()).isEqualByComparingTo("11000.00");
        // banka dobija proviziju (25)
        assertThat(bankTradingAccount.getBalance()).isEqualByComparingTo("100025.00");
        verify(accountRepository).save(bankTradingAccount);
    }

    @Test
    void credit_wrongCurrency_throwsIllegalArgumentException() {
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));

        CreditFundsRequest req = new CreditFundsRequest(
                1L, new BigDecimal("100.00"), BigDecimal.ZERO, "EUR", "test");
        assertThatThrownBy(() -> service.credit(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Valuta");
    }

    @Test
    void credit_nonPositiveAmount_throwsIllegalArgumentException() {
        CreditFundsRequest req = new CreditFundsRequest(
                1L, BigDecimal.ZERO, BigDecimal.ZERO, "RSD", "test");
        assertThatThrownBy(() -> service.credit(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pozitivan");
    }

    @Test
    void creditIdempotent_cachedKey_returnsCachedResponseWithoutReExecuting() throws Exception {
        String cachedJson = "{\"accountId\":1,\"creditedAmount\":500.00,\"balanceAfter\":10500.00}";
        InternalRequest cachedRequest = new InternalRequest();
        cachedRequest.setIdempotencyKey("idem-credit-1");
        cachedRequest.setEndpoint("/internal/funds/credit");
        cachedRequest.setHttpStatus(200);
        cachedRequest.setResponseBody(cachedJson);

        when(idempotencyService.findCached("idem-credit-1")).thenReturn(Optional.of(cachedRequest));
        CreditFundsResponse expected = new CreditFundsResponse(
                1L, new BigDecimal("500.00"), new BigDecimal("10500.00"));
        when(objectMapper.readValue(cachedJson, CreditFundsResponse.class)).thenReturn(expected);

        CreditFundsRequest req = new CreditFundsRequest(
                1L, new BigDecimal("500.00"), BigDecimal.ZERO, "RSD", "SELL prihod");
        CreditFundsResponse response = service.creditIdempotent("idem-credit-1", req);

        assertThat(response.creditedAmount()).isEqualByComparingTo("500.00");
        // Cached path — nijedna mutirajuca operacija
        verify(accountRepository, never()).findForUpdateById(any());
        verify(accountRepository, never()).save(any());
    }

    // ─── debit tests ──────────────────────────────────────────────────────────

    @Test
    void debit_noCommission_debitsAccountOneSided() {
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DebitFundsRequest req = new DebitFundsRequest(
                1L, new BigDecimal("650.00"), BigDecimal.ZERO, "RSD", "Option exercise CALL");
        DebitFundsResponse response = service.debit(req);

        assertThat(response.accountId()).isEqualTo(1L);
        assertThat(response.debitedAmount()).isEqualByComparingTo("650.00");
        // balance 10000 - 650 = 9350 — bez ijednog credit-a (trziste je apstraktan ponor)
        assertThat(response.balanceAfter()).isEqualByComparingTo("9350.00");
        assertThat(account.getBalance()).isEqualByComparingTo("9350.00");
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("9350.00");
        verify(accountRepository, never())
                .findByAccountCategoryAndCurrencyCodeForUpdate(any(), anyString());
    }

    @Test
    void debit_withCommission_debitsAccountAndCreditsBank() {
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findByAccountCategoryAndCurrencyCodeForUpdate(
                AccountCategory.BANK_TRADING, "RSD")).thenReturn(List.of(bankTradingAccount));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DebitFundsRequest req = new DebitFundsRequest(
                1L, new BigDecimal("1000.00"), new BigDecimal("30.00"), "RSD", "Margin uplata");
        DebitFundsResponse response = service.debit(req);

        assertThat(response.debitedAmount()).isEqualByComparingTo("1000.00");
        // racun: 10000 - 1000 = 9000 (provizija ne dira debit-ovani racun)
        assertThat(account.getBalance()).isEqualByComparingTo("9000.00");
        // banka dobija proviziju (30)
        assertThat(bankTradingAccount.getBalance()).isEqualByComparingTo("100030.00");
        assertThat(bankTradingAccount.getAvailableBalance()).isEqualByComparingTo("100030.00");
        verify(accountRepository).save(bankTradingAccount);
    }

    @Test
    void debit_insufficientFunds_throwsIllegalStateException() {
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));

        DebitFundsRequest req = new DebitFundsRequest(
                1L, new BigDecimal("99999.00"), BigDecimal.ZERO, "RSD", "Option exercise");
        assertThatThrownBy(() -> service.debit(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nedovoljno raspolozivih sredstava");

        verify(accountRepository, never()).save(any());
    }

    @Test
    void debit_wrongCurrency_throwsIllegalArgumentException() {
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));

        DebitFundsRequest req = new DebitFundsRequest(
                1L, new BigDecimal("100.00"), BigDecimal.ZERO, "EUR", "test");
        assertThatThrownBy(() -> service.debit(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Valuta");
    }

    @Test
    void debit_nonPositiveAmount_throwsIllegalArgumentException() {
        DebitFundsRequest req = new DebitFundsRequest(
                1L, BigDecimal.ZERO, BigDecimal.ZERO, "RSD", "test");
        assertThatThrownBy(() -> service.debit(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pozitivan");
    }

    @Test
    void debit_nonExistentAccount_throwsIllegalArgumentException() {
        when(accountRepository.findForUpdateById(99L)).thenReturn(Optional.empty());

        DebitFundsRequest req = new DebitFundsRequest(
                99L, new BigDecimal("100.00"), BigDecimal.ZERO, "RSD", "test");
        assertThatThrownBy(() -> service.debit(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Racun ne postoji");
    }

    @Test
    void debitIdempotent_cachedKey_returnsCachedResponseWithoutReExecuting() throws Exception {
        String cachedJson = "{\"accountId\":1,\"debitedAmount\":400.00,\"balanceAfter\":9600.00}";
        InternalRequest cachedRequest = new InternalRequest();
        cachedRequest.setIdempotencyKey("idem-debit-1");
        cachedRequest.setEndpoint("/internal/funds/debit");
        cachedRequest.setHttpStatus(200);
        cachedRequest.setResponseBody(cachedJson);

        when(idempotencyService.findCached("idem-debit-1")).thenReturn(Optional.of(cachedRequest));
        DebitFundsResponse expected = new DebitFundsResponse(
                1L, new BigDecimal("400.00"), new BigDecimal("9600.00"));
        when(objectMapper.readValue(cachedJson, DebitFundsResponse.class)).thenReturn(expected);

        DebitFundsRequest req = new DebitFundsRequest(
                1L, new BigDecimal("400.00"), BigDecimal.ZERO, "RSD", "Option exercise CALL");
        DebitFundsResponse response = service.debitIdempotent("idem-debit-1", req);

        assertThat(response.debitedAmount()).isEqualByComparingTo("400.00");
        // Cached path — nijedna mutirajuca operacija
        verify(accountRepository, never()).findForUpdateById(any());
        verify(accountRepository, never()).save(any());
    }

    // ─── tax-collect tests ────────────────────────────────────────────────────

    @Test
    void collectTax_happyPath_debitsClientAndCreditsState() {
        Account stateAccount = buildPlainAccount("178000000000000001", "0.00", 50L);
        Account clientRsd = buildPlainAccount("222000300000000001", "5000.00", 10L);

        when(accountRepository.findBankAccountForUpdateByCurrency("17858459", "RSD"))
                .thenReturn(Optional.of(stateAccount));
        when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(
                100L, AccountStatus.ACTIVE)).thenReturn(List.of(clientRsd));
        when(accountRepository.findForUpdateById(10L)).thenReturn(Optional.of(clientRsd));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TaxCollectRequest req = new TaxCollectRequest(100L, new BigDecimal("300.00"), "Porez 2026-05");
        TaxCollectResponse response = service.collectTax(req);

        assertThat(response.collected()).isTrue();
        assertThat(response.collectedAmount()).isEqualByComparingTo("300.00");
        // klijent debitovan
        assertThat(clientRsd.getBalance()).isEqualByComparingTo("4700.00");
        assertThat(clientRsd.getAvailableBalance()).isEqualByComparingTo("4700.00");
        // drzava kreditovana
        assertThat(stateAccount.getBalance()).isEqualByComparingTo("300.00");
        assertThat(stateAccount.getAvailableBalance()).isEqualByComparingTo("300.00");
    }

    @Test
    void collectTax_insufficientFunds_returnsCollectedFalseWithoutThrowing() {
        Account stateAccount = buildPlainAccount("178000000000000001", "0.00", 50L);
        // klijentov RSD racun ima samo 100 — nedovoljno za porez 300
        Account clientRsd = buildPlainAccount("222000300000000002", "100.00", 11L);

        when(accountRepository.findBankAccountForUpdateByCurrency("17858459", "RSD"))
                .thenReturn(Optional.of(stateAccount));
        when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(
                101L, AccountStatus.ACTIVE)).thenReturn(List.of(clientRsd));

        TaxCollectRequest req = new TaxCollectRequest(101L, new BigDecimal("300.00"), "Porez");
        TaxCollectResponse response = service.collectTax(req);

        // Verno monolitu: NE baca izuzetak, samo collected=false
        assertThat(response.collected()).isFalse();
        assertThat(response.collectedAmount()).isEqualByComparingTo("0.00");
        assertThat(response.payerClientId()).isEqualTo(101L);
        // nijedan racun nije diran
        assertThat(clientRsd.getBalance()).isEqualByComparingTo("100.00");
        assertThat(stateAccount.getBalance()).isEqualByComparingTo("0.00");
        verify(accountRepository, never()).save(any());
    }

    @Test
    void collectTax_noStateAccount_returnsCollectedFalse() {
        when(accountRepository.findBankAccountForUpdateByCurrency("17858459", "RSD"))
                .thenReturn(Optional.empty());

        TaxCollectRequest req = new TaxCollectRequest(102L, new BigDecimal("300.00"), "Porez");
        TaxCollectResponse response = service.collectTax(req);

        assertThat(response.collected()).isFalse();
        assertThat(response.collectedAmount()).isEqualByComparingTo("0.00");
        verify(accountRepository, never()).save(any());
    }

    @Test
    void collectTax_eurOnlyClient_collectsFromEurAccountViaFx() {
        // P0-B3 (EUR-only fix): klijent ima SAMO EUR racun (dobit ostvarena u EUR).
        // Pre fix-a porez se NIKAD nije naplacivao (collected=false). Sad se RSD iznos
        // poreza konvertuje u EUR (srednji kurs) i nativni ekvivalent se skida sa EUR
        // racuna; drzava i dalje dobija RSD iznos (conservation).
        Account stateAccount = buildPlainAccount("178000000000000001", "0.00", 50L);
        Account clientEur = buildPlainAccount("222000300000000003", "5000.00", 12L, eur);

        when(accountRepository.findBankAccountForUpdateByCurrency("17858459", "RSD"))
                .thenReturn(Optional.of(stateAccount));
        when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(
                103L, AccountStatus.ACTIVE)).thenReturn(List.of(clientEur));
        when(accountRepository.findForUpdateById(12L)).thenReturn(Optional.of(clientEur));
        // 300 RSD ≈ 2.5594 EUR (srednji kurs)
        when(currencyConversionService.convert(new BigDecimal("300.00"), "RSD", "EUR"))
                .thenReturn(new BigDecimal("2.5594"));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TaxCollectRequest req = new TaxCollectRequest(103L, new BigDecimal("300.00"), "Porez");
        TaxCollectResponse response = service.collectTax(req);

        // Naplaceno (collectedAmount izvestava RSD iznos poreza).
        assertThat(response.collected()).isTrue();
        assertThat(response.collectedAmount()).isEqualByComparingTo("300.00");
        // EUR racun debitovan FX-ekvivalentom (5000 - 2.5594).
        assertThat(clientEur.getBalance()).isEqualByComparingTo("4997.4406");
        assertThat(clientEur.getAvailableBalance()).isEqualByComparingTo("4997.4406");
        // Drzava dobija RSD iznos poreza (conservation).
        assertThat(stateAccount.getBalance()).isEqualByComparingTo("300.00");
        assertThat(stateAccount.getAvailableBalance()).isEqualByComparingTo("300.00");
    }

    @Test
    void collectTax_noAccountCoversEvenAfterFx_returnsCollectedFalse() {
        // Jedini racun je EUR sa premalo sredstava i posle FX-a → preskoci, collected=false.
        Account stateAccount = buildPlainAccount("178000000000000001", "0.00", 50L);
        Account clientEur = buildPlainAccount("222000300000000009", "1.00", 13L, eur);

        when(accountRepository.findBankAccountForUpdateByCurrency("17858459", "RSD"))
                .thenReturn(Optional.of(stateAccount));
        when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(
                104L, AccountStatus.ACTIVE)).thenReturn(List.of(clientEur));
        // 300 RSD ≈ 2.5594 EUR > 1.00 EUR raspolozivo → ne pokriva
        when(currencyConversionService.convert(new BigDecimal("300.00"), "RSD", "EUR"))
                .thenReturn(new BigDecimal("2.5594"));

        TaxCollectRequest req = new TaxCollectRequest(104L, new BigDecimal("300.00"), "Porez");
        TaxCollectResponse response = service.collectTax(req);

        assertThat(response.collected()).isFalse();
        verify(accountRepository, never()).save(any());
    }

    @Test
    void collectTaxIdempotent_cachedKey_returnsCachedResponseWithoutReExecuting() throws Exception {
        String cachedJson = "{\"payerClientId\":100,\"collectedAmount\":300.00,\"collected\":true}";
        InternalRequest cachedRequest = new InternalRequest();
        cachedRequest.setIdempotencyKey("idem-tax-1");
        cachedRequest.setEndpoint("/internal/funds/tax-collect");
        cachedRequest.setHttpStatus(200);
        cachedRequest.setResponseBody(cachedJson);

        when(idempotencyService.findCached("idem-tax-1")).thenReturn(Optional.of(cachedRequest));
        TaxCollectResponse expected = new TaxCollectResponse(100L, new BigDecimal("300.00"), true);
        when(objectMapper.readValue(cachedJson, TaxCollectResponse.class)).thenReturn(expected);

        TaxCollectRequest req = new TaxCollectRequest(100L, new BigDecimal("300.00"), "Porez");
        TaxCollectResponse response = service.collectTaxIdempotent("idem-tax-1", req);

        assertThat(response.collected()).isTrue();
        verify(accountRepository, never()).findBankAccountForUpdateByCurrency(anyString(), anyString());
        verify(accountRepository, never()).save(any());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private FundReservation buildReservation(String rid, Long accountId,
                                              String amount, String committed,
                                              FundReservationStatus status) {
        FundReservation r = new FundReservation();
        r.setReservationId(rid);
        r.setAccountId(accountId);
        r.setAmount(new BigDecimal(amount));
        r.setCommittedAmount(new BigDecimal(committed));
        r.setCurrencyCode("RSD");
        r.setStatus(status);
        return r;
    }

    /** RSD CHECKING racun sa datim brojem, balansom i ID-em (balance == available). */
    private Account buildPlainAccount(String accountNumber, String balance, Long id) {
        return buildPlainAccount(accountNumber, balance, id, rsd);
    }

    /** CHECKING racun u datoj valuti sa datim brojem, balansom i ID-em (balance == available). */
    private Account buildPlainAccount(String accountNumber, String balance, Long id, Currency currency) {
        Account a = Account.builder()
                .accountNumber(accountNumber)
                .accountType(AccountType.CHECKING)
                .currency(currency)
                .balance(new BigDecimal(balance))
                .availableBalance(new BigDecimal(balance))
                .reservedAmount(BigDecimal.ZERO)
                .dailyLimit(BigDecimal.ZERO)
                .monthlyLimit(BigDecimal.ZERO)
                .dailySpending(BigDecimal.ZERO)
                .monthlySpending(BigDecimal.ZERO)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(a, "id", id);
        return a;
    }
}
