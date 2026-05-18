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
import rs.raf.banka2.contracts.internal.ReleaseFundsRequest;
import rs.raf.banka2.contracts.internal.ReleaseFundsResponse;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.banka2.contracts.internal.TransferFundsResponse;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountType;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.internalapi.model.FundReservation;
import rs.raf.banka2_bek.internalapi.model.FundReservationStatus;
import rs.raf.banka2_bek.internalapi.model.InternalRequest;
import rs.raf.banka2_bek.internalapi.repository.FundReservationRepository;
import rs.raf.banka2_bek.internalapi.service.InternalFundsService;
import rs.raf.banka2_bek.internalapi.service.InternalIdempotencyService;
import rs.raf.banka2_bek.transaction.repository.TransactionRepository;

import java.math.BigDecimal;
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

    @InjectMocks InternalFundsService service;

    private Currency rsd;
    private Account account;

    @BeforeEach
    void setUp() {
        rsd = new Currency();
        rsd.setId(1L);
        rsd.setCode("RSD");
        rsd.setName("Srpski dinar");
        rsd.setSymbol("din");
        rsd.setCountry("RS");

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
    void commit_happyPath_decreasesBalanceAndReserved() {
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
    void transfer_happyPath_debitsFromCreditTo() {
        Account toAccount = Account.builder()
                .accountNumber("222000200000000003")
                .accountType(AccountType.CHECKING)
                .currency(rsd)
                .balance(new BigDecimal("500.00"))
                .availableBalance(new BigDecimal("500.00"))
                .reservedAmount(BigDecimal.ZERO)
                .dailyLimit(BigDecimal.ZERO)
                .monthlyLimit(BigDecimal.ZERO)
                .dailySpending(BigDecimal.ZERO)
                .monthlySpending(BigDecimal.ZERO)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(toAccount, "id", 2L);

        // transfer locks in min-id order: 1 then 2
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findForUpdateById(2L)).thenReturn(Optional.of(toAccount));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransferFundsRequest req = new TransferFundsRequest(1L, 2L, new BigDecimal("200.00"), "RSD", "dividend");
        TransferFundsResponse response = service.transfer(req);

        assertThat(response.fromAccountId()).isEqualTo(1L);
        assertThat(response.toAccountId()).isEqualTo(2L);
        assertThat(response.amount()).isEqualByComparingTo("200.00");
        assertThat(account.getBalance()).isEqualByComparingTo("9800.00");
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("9800.00");
        assertThat(toAccount.getBalance()).isEqualByComparingTo("700.00");
        assertThat(toAccount.getAvailableBalance()).isEqualByComparingTo("700.00");
    }

    @Test
    void transfer_insufficientFunds_throwsIllegalStateException() {
        Account toAccount = Account.builder()
                .accountNumber("222000200000000004")
                .accountType(AccountType.CHECKING)
                .currency(rsd)
                .balance(new BigDecimal("0.00"))
                .availableBalance(new BigDecimal("0.00"))
                .reservedAmount(BigDecimal.ZERO)
                .dailyLimit(BigDecimal.ZERO)
                .monthlyLimit(BigDecimal.ZERO)
                .dailySpending(BigDecimal.ZERO)
                .monthlySpending(BigDecimal.ZERO)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(toAccount, "id", 2L);

        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findForUpdateById(2L)).thenReturn(Optional.of(toAccount));

        TransferFundsRequest req = new TransferFundsRequest(1L, 2L, new BigDecimal("99999.00"), "RSD", "test");
        assertThatThrownBy(() -> service.transfer(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nedovoljno raspolozivih sredstava");

        verify(accountRepository, never()).save(any());
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
}
