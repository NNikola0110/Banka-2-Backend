package rs.raf.banka2_bek.payment.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.exchange.ExchangeService;
import rs.raf.banka2_bek.exchange.dto.ExchangeRateDto;
import rs.raf.banka2_bek.payment.dto.CreatePaymentRequestDto;
import rs.raf.banka2_bek.payment.dto.PaymentResponseDto;
import rs.raf.banka2_bek.payment.model.PaymentCode;
import rs.raf.banka2_bek.payment.model.Payment;
import rs.raf.banka2_bek.payment.model.PaymentStatus;
import rs.raf.banka2_bek.payment.exception.OtpInvalidException;
import rs.raf.banka2_bek.payment.exception.OtpLockedException;
import rs.raf.banka2_bek.payment.exception.PaymentAlreadyFinalizedException;
import rs.raf.banka2_bek.payment.exception.PaymentNotFoundException;
import rs.raf.banka2_bek.payment.exception.PaymentNotOwnedException;
import rs.raf.banka2_bek.payment.exception.PaymentTimeoutException;
import rs.raf.banka2_bek.payment.exception.QuickApproveSettlementNotWiredException;
import rs.raf.banka2_bek.payment.repository.PaymentAccountRepository;
import rs.raf.banka2_bek.payment.repository.PaymentRepository;
import rs.raf.banka2_bek.payment.service.implementation.PaymentServiceImpl;
import rs.raf.banka2_bek.otp.service.OtpService;
import org.springframework.test.util.ReflectionTestUtils;
import rs.raf.banka2_bek.notification.NotificationPublisher;
import rs.raf.banka2_bek.notification.service.NotificationService;
import rs.raf.banka2_bek.interbank.service.BankRoutingService;
import rs.raf.banka2_bek.interbank.service.TransactionExecutorService;
import rs.raf.banka2_bek.interbank.service.InterbankPaymentAsyncService;
import rs.raf.banka2_bek.interbank.repository.InterbankTransactionRepository;
import rs.raf.banka2_bek.transaction.dto.TransactionResponseDto;
import rs.raf.banka2_bek.transaction.dto.TransactionType;
import rs.raf.banka2_bek.transaction.service.TransactionService;
import rs.raf.banka2_bek.client.model.Client;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentAccountRepository paymentAccountRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private ClientRepository clientRepository;
    @Mock
    private TransactionService transactionService;
    @Mock
    private PaymentReceiptPdfGenerator paymentReceiptPdfGenerator;
    @Mock
    private ExchangeService exchangeService;
    @Mock
    private NotificationPublisher notificationPublisher;
    @Mock
    private BankRoutingService bankRoutingService;
    @Mock
    private TransactionExecutorService transactionExecutorService;
    @Mock
    private InterbankPaymentAsyncService interbankPaymentAsyncService;
    @Mock
    private InterbankTransactionRepository interbankTransactionRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private rs.raf.banka2_bek.audit.service.AuditLogService auditLogService;
    @Mock
    private OtpService otpService;

    private PaymentServiceImpl paymentService;

    private CreatePaymentRequestDto request;
    private Client client;
    private Currency eur;
    private Account fromAccount;
    private Account toAccount;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentServiceImpl(
                paymentRepository, paymentAccountRepository, accountRepository,
                clientRepository, transactionService, paymentReceiptPdfGenerator,
                exchangeService, notificationPublisher,
                bankRoutingService, transactionExecutorService,
                interbankPaymentAsyncService, interbankTransactionRepository,
                "22200022", notificationService, auditLogService);

        // OtpService je @Autowired private field (ne ide kroz ctor) — inject mock preko reflection.
        ReflectionTestUtils.setField(paymentService, "otpService", otpService);

        lenient().when(bankRoutingService.isLocalAccount(any())).thenReturn(true);

        request = new CreatePaymentRequestDto();
        request.setFromAccount("111111111111111111");
        request.setToAccount("222222222222222222");
        request.setAmount(new BigDecimal("100.00"));
        request.setPaymentCode(PaymentCode.CODE_289);
        request.setReferenceNumber("REF-1");
        request.setDescription("Test payment");

        client = new Client();
        client.setId(10L);
        client.setEmail("client@test.com");
        client.setActive(true);
//        client.setRole("CLIENT");

        eur = Currency.builder().id(1L).code("EUR").name("Euro").symbol("E").country("EU").active(true).build();

        fromAccount = baseAccount(1L, request.getFromAccount(), client, eur, new BigDecimal("1000.00"));
        toAccount = baseAccount(2L, request.getToAccount(), null, eur, new BigDecimal("500.00"));

        authenticateAs(client.getEmail());
        lenient().when(clientRepository.findByEmail(client.getEmail())).thenReturn(Optional.of(client));
        lenient().when(exchangeService.getAllRates()).thenReturn(defaultRates());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createPayment_success_updatesBalances_savesPayment_andRecordsTransactions() {
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));

        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(99L);
            p.setCreatedAt(LocalDateTime.now());
            return p;
        });

        PaymentResponseDto response = paymentService.createPayment(request);

        assertThat(response.getId()).isEqualTo(99L);
        assertThat(response.getStatus().name()).isEqualTo("COMPLETED");
        assertThat(response.getAmount()).isEqualByComparingTo("100.00");

        assertThat(fromAccount.getBalance()).isEqualByComparingTo("900.00");
        assertThat(fromAccount.getAvailableBalance()).isEqualByComparingTo("900.00");
        assertThat(fromAccount.getDailySpending()).isEqualByComparingTo("100.00");
        assertThat(fromAccount.getMonthlySpending()).isEqualByComparingTo("100.00");

        assertThat(toAccount.getBalance()).isEqualByComparingTo("600.00");
        assertThat(toAccount.getAvailableBalance()).isEqualByComparingTo("600.00");

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getOrderNumber()).startsWith("PAY-");
        assertThat(paymentCaptor.getValue().getFee()).isEqualByComparingTo("0");

        verify(transactionService).recordPaymentSettlement(
                any(Payment.class),
                eq(toAccount),
                eq(client),
                argThat(credited -> credited.compareTo(new BigDecimal("100.00")) == 0)
        );
    }

    @Test
    void createPayment_success_crossCurrency_appliesFeeAndFxRate() {
        Currency usd = Currency.builder()
                .id(2L)
                .code("USD")
                .name("US Dollar")
                .symbol("$")
                .country("US")
                .active(true)
                .build();
        toAccount.setCurrency(usd);

        Account bankEurAccount = baseAccount(100L, "BANK-EUR", null, eur, new BigDecimal("1000000"));
        Account bankUsdAccount = baseAccount(101L, "BANK-USD", null, usd, new BigDecimal("1000000"));

        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount()))
                .thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount()))
                .thenReturn(Optional.of(toAccount));
        when(accountRepository.findBankAccountForUpdateByCurrency("22200022", "EUR"))
                .thenReturn(Optional.of(bankEurAccount));
        when(accountRepository.findBankAccountForUpdateByCurrency("22200022", "USD"))
                .thenReturn(Optional.of(bankUsdAccount));
        when(exchangeService.calculateCrossExact(new BigDecimal("100.00"), "EUR", "USD"))
                .thenReturn(new ExchangeService.FxConversionResult(new BigDecimal("108.02"), new BigDecimal("1.080184")));

        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(200L);
            p.setCreatedAt(LocalDateTime.now());
            return p;
        });

        PaymentResponseDto response = paymentService.createPayment(request);

        assertThat(response.getId()).isEqualTo(200L);
        assertThat(response.getStatus().name()).isEqualTo("COMPLETED");

        // Fee = 0.5% of 100.00 = 0.50000
        assertThat(fromAccount.getBalance()).isEqualByComparingTo("899.50000");
        assertThat(fromAccount.getAvailableBalance()).isEqualByComparingTo("899.50000");

        // credited amount = 108.02 (BigDecimal money scale 2, bez sub-cent repa)
        assertThat(toAccount.getBalance()).isEqualByComparingTo("608.02");
        assertThat(toAccount.getAvailableBalance()).isEqualByComparingTo("608.02");

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getFee()).isEqualByComparingTo("0.50000");

        verify(transactionService).recordPaymentSettlement(
                any(Payment.class),
                eq(toAccount),
                eq(client),
                argThat(credited -> credited.compareTo(new BigDecimal("108.02")) == 0)
        );
    }


    @Test
    void createPayment_retriesOnUniqueViolation_thenSucceeds() {
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));

        AtomicInteger attempts = new AtomicInteger(0);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            if (attempts.getAndIncrement() == 0) {
                throw uniqueViolation();
            }
            Payment p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });

        PaymentResponseDto response = paymentService.createPayment(request);

        assertThat(response.getId()).isEqualTo(1L);
        verify(paymentRepository, times(2)).saveAndFlush(any(Payment.class));
    }

    @Test
    void createPayment_throwsWhenAllUniqueRetriesFail() {
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenThrow(uniqueViolation());

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Generisanje broja placanja nije uspelo");
    }

    @Test
    void createPayment_propagatesDataIntegrityViolationWhenCauseMessageIsNull() {
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));

        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "constraint",
                new RuntimeException()
        );
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenThrow(ex);

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isSameAs(ex);
    }

    @Test
    void createPayment_propagatesNonUniqueDataIntegrityViolation() {
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));

        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "constraint",
                new RuntimeException("Cannot add or update child row")
        );
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenThrow(ex);

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isSameAs(ex);
    }

    @Test
    void createPayment_throwsWhenFromAccountMissing() {
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Racun posiljaoca ne postoji");
    }

    @Test
    void createPayment_throwsWhenToAccountMissing() {
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Racun primaoca ne postoji");
    }

    @Test
    void createPayment_throwsWhenFromAccountInactive() {
        // P2-concurrency-locks-1 (R3-1581): lokalni flow sada zakljucava OBA racuna
        // kanonski PRE posiljalac-validacije → primalac mora biti stub-ovan.
        fromAccount.setStatus(AccountStatus.INACTIVE);
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Racun posiljaoca nije aktivan");
    }

    @Test
    void createPayment_throwsWhenToAccountInactive() {
        toAccount.setStatus(AccountStatus.INACTIVE);
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Racun primaoca nije aktivan");
    }

    @Test
    void createPayment_throwsWhenSameAccount() {
        toAccount.setId(fromAccount.getId());
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Racuni moraju biti razliciti");
    }

    @Test
    void createPayment_throwsWhenFromAccountNotOwnedByAuthenticatedUser() {
        // P2-concurrency-locks-1 (R3-1581): lokalni flow zakljucava OBA racuna kanonski
        // PRE vlasnistvo-provere → primalac mora biti stub-ovan.
        Client other = new Client();
        other.setId(777L);
        fromAccount.setClient(other);

        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ne pripada klijentu");
    }

    @Test
    void createPayment_throwsWhenDailyLimitExceeded() {
        fromAccount.setDailyLimit(new BigDecimal("50.00"));

        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prekoracen dnevni limit");
    }

    @Test
    void createPayment_succeedsWhenDailyLimitMissing() {
        fromAccount.setDailyLimit(null);

        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(1L);
            p.setCreatedAt(java.time.LocalDateTime.now());
            return p;
        });

        PaymentResponseDto response = paymentService.createPayment(request);
        assertThat(response.getId()).isEqualTo(1L);
    }

    @Test
    void createPayment_throwsWhenMonthlyLimitExceeded() {
        fromAccount.setMonthlyLimit(new BigDecimal("70.00"));

        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prekoracen mesecni limit");
    }

    @Test
    void createPayment_succeedsWhenMonthlyLimitMissing() {
        fromAccount.setMonthlyLimit(null);

        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(1L);
            p.setCreatedAt(java.time.LocalDateTime.now());
            return p;
        });

        PaymentResponseDto response = paymentService.createPayment(request);
        assertThat(response.getId()).isEqualTo(1L);
    }

    @Test
    void createPayment_throwsWhenInsufficientFunds() {
        fromAccount.setAvailableBalance(new BigDecimal("20.00"));

        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nedovoljno sredstava");
    }

    @Test
    void createPayment_throwsWhenAvailableBalanceMissing() {
        fromAccount.setAvailableBalance(null);

        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void createPayment_throwsWhenAuthenticationMissing() {
        SecurityContextHolder.clearContext();

        // P2-concurrency-locks-1 (R3-1581): lokalni flow zakljucava OBA racuna kanonski
        // PRE auth-provere → primalac mora biti stub-ovan da se auth grana dosegne.
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getPaymentReceipt_returnsPdfForOwnedTransaction() {
        TransactionResponseDto transaction = TransactionResponseDto.builder()
                .id(55L)
                .type(TransactionType.PAYMENT)
                .accountNumber(fromAccount.getAccountNumber())
                .currencyCode(eur.getCode())
                .debit(new BigDecimal("50.00"))
                .build();

        byte[] expected = "%PDF-test".getBytes();

        when(transactionService.getReceiptTransaction(55L, client.getId())).thenReturn(transaction);
        when(paymentReceiptPdfGenerator.generate(transaction)).thenReturn(expected);

        byte[] result = paymentService.getPaymentReceipt(55L);

        assertThat(result).isEqualTo(expected);
        verify(paymentReceiptPdfGenerator).generate(transaction);
    }

    @Test
    void getPaymentReceipt_throwsWhenTransactionNotOwnedOrMissing() {
        when(transactionService.getReceiptTransaction(55L, client.getId()))
                .thenThrow(new IllegalArgumentException("Transaction with ID 55 not found for authenticated client."));

        // Bug-3 fix: kad transaction ledger nema zapis (inter-bank placanja), getPaymentReceipt
        // fallback-uje na sam Payment. Posto Payment 55 ne postoji (paymentRepository.findById
        // vraca empty), rezultujuci izuzetak je PaymentNotFoundException — vise NE originalni
        // IllegalArgumentException iz getReceiptTransaction.
        assertThatThrownBy(() -> paymentService.getPaymentReceipt(55L))
                .isInstanceOf(rs.raf.banka2_bek.payment.exception.PaymentNotFoundException.class);
    }

    // ========== getPaymentById — P2-1 IDOR ownership guard ==========

    @Test
    void getPaymentById_returnsPaymentWhenClientIsPayer() {
        // fromAccount pripada autentifikovanom klijentu -> sme da vidi placanje.
        Payment payment = Payment.builder()
                .id(42L)
                .orderNumber("PAY-ABC")
                .fromAccount(fromAccount)
                .toAccountNumber(request.getToAccount())
                .amount(new BigDecimal("100.00"))
                .fee(BigDecimal.ZERO)
                .currency(eur)
                .status(PaymentStatus.COMPLETED)
                .createdBy(client)
                .createdAt(LocalDateTime.now())
                .build();
        when(paymentRepository.findById(42L)).thenReturn(Optional.of(payment));

        PaymentResponseDto response = paymentService.getPaymentById(42L);

        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getDirection().name()).isEqualTo("OUTGOING");
    }

    @Test
    void getPaymentById_returnsPaymentWhenClientIsRecipient() {
        // fromAccount pripada drugom klijentu, ali toAccountNumber je lokalni
        // racun autentifikovanog klijenta -> primalac sme da vidi placanje.
        Client other = new Client();
        other.setId(777L);
        Account otherFrom = baseAccount(5L, "555555555555555555", other, eur, new BigDecimal("1000.00"));
        Account myRecipient = baseAccount(6L, "666666666666666666", client, eur, new BigDecimal("200.00"));

        Payment payment = Payment.builder()
                .id(43L)
                .orderNumber("PAY-DEF")
                .fromAccount(otherFrom)
                .toAccountNumber(myRecipient.getAccountNumber())
                .amount(new BigDecimal("50.00"))
                .fee(BigDecimal.ZERO)
                .currency(eur)
                .status(PaymentStatus.COMPLETED)
                .createdBy(other)
                .createdAt(LocalDateTime.now())
                .build();
        when(paymentRepository.findById(43L)).thenReturn(Optional.of(payment));
        when(accountRepository.findByAccountNumber(myRecipient.getAccountNumber()))
                .thenReturn(Optional.of(myRecipient));

        PaymentResponseDto response = paymentService.getPaymentById(43L);

        assertThat(response.getId()).isEqualTo(43L);
        assertThat(response.getDirection().name()).isEqualTo("INCOMING");
    }

    @Test
    void getPaymentById_throwsWhenClientIsNotParty() {
        // P2-1 IDOR: placanje izmedju dva DRUGA klijenta — autentifikovani klijent
        // nije ni platilac ni primalac -> PaymentNotOwnedException (HTTP 403),
        // ne sme da vidi tudje podatke (iznos, racuni, primalac, svrha).
        Client other = new Client();
        other.setId(777L);
        Account otherFrom = baseAccount(7L, "777777777777777777", other, eur, new BigDecimal("1000.00"));

        Payment payment = Payment.builder()
                .id(44L)
                .orderNumber("PAY-GHI")
                .fromAccount(otherFrom)
                .toAccountNumber("888888888888888888")
                .amount(new BigDecimal("999.00"))
                .fee(BigDecimal.ZERO)
                .currency(eur)
                .status(PaymentStatus.COMPLETED)
                .createdBy(other)
                .createdAt(LocalDateTime.now())
                .build();
        when(paymentRepository.findById(44L)).thenReturn(Optional.of(payment));
        // toAccountNumber nije lokalni racun klijenta (ili je u drugoj banci)
        when(accountRepository.findByAccountNumber("888888888888888888"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPaymentById(44L))
                .isInstanceOf(rs.raf.banka2_bek.payment.exception.PaymentNotOwnedException.class)
                .hasMessageContaining("ne pripada korisniku");
    }

    @Test
    void getPaymentById_throwsNotFoundWhenPaymentMissing() {
        // R1 330: nepostojece placanje je 404 (PaymentNotFoundException), ne 400.
        when(paymentRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPaymentById(404L))
                .isInstanceOf(rs.raf.banka2_bek.payment.exception.PaymentNotFoundException.class)
                .hasMessageContaining("nije pronadjeno");
    }

    @Test
    void createPayment_throwsWhenPrincipalIsNotUserDetails() {
        // Edge case: principal nije UserDetails (npr. anonimni/raw string token).
        // getAuthenticatedUsername() odbije takav principal, pa getAuthenticatedClient()
        // ne uspe da razresi klijenta -> IllegalArgumentException("Klijent nije pronadjen.").
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "rawPrincipal",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CLIENT"))
                )
        );

        // P2-concurrency-locks-1 (R3-1581): lokalni flow zakljucava OBA racuna kanonski
        // PRE auth-provere → primalac mora biti stub-ovan da se klijent-grana dosegne.
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount()))
                .thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount()))
                .thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Klijent nije pronadjen");
    }

    @Test
    void createPayment_throwsWhenAuthenticatedUserNotFoundInDb() {
        // Edge case: autentifikovan korisnik (validan UserDetails) ali bez Client zapisa u bazi.
        // getAuthenticatedClient() vrati null -> IllegalArgumentException("Klijent nije pronadjen.").
        authenticateAs("missing@test.com");
        when(clientRepository.findByEmail("missing@test.com")).thenReturn(Optional.empty());

        // P2-concurrency-locks-1 (R3-1581): lokalni flow zakljucava OBA racuna kanonski PRE auth.
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount()))
                .thenReturn(Optional.of(fromAccount));
        when(paymentAccountRepository.findForUpdateByAccountNumber(request.getToAccount()))
                .thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Klijent nije pronadjen");
    }


    // ========== validatePayment ==========

    @Test
    void validatePayment_throwsWhenRequestNull() {
        assertThatThrownBy(() -> paymentService.validatePayment(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Podaci o placanju nedostaju");
    }

    @Test
    void validatePayment_throwsWhenFromAccountBlank() {
        request.setFromAccount("");
        assertThatThrownBy(() -> paymentService.validatePayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Racun posiljaoca nedostaje");
    }

    @Test
    void validatePayment_throwsWhenToAccountNull() {
        request.setToAccount(null);
        assertThatThrownBy(() -> paymentService.validatePayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Racun primaoca nedostaje");
    }

    @Test
    void validatePayment_throwsWhenAmountZero() {
        request.setAmount(BigDecimal.ZERO);
        assertThatThrownBy(() -> paymentService.validatePayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Iznos mora biti veci od 0");
    }

    @Test
    void validatePayment_throwsWhenFromAccountNotFound() {
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> paymentService.validatePayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Racun posiljaoca ne postoji");
    }

    @Test
    void validatePayment_throwsWhenToAccountNotFound() {
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber(request.getToAccount())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> paymentService.validatePayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Racun primaoca ne postoji");
    }

    @Test
    void validatePayment_throwsWhenFromAccountInactive() {
        fromAccount.setStatus(AccountStatus.INACTIVE);
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));
        assertThatThrownBy(() -> paymentService.validatePayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Racun posiljaoca nije aktivan");
    }

    @Test
    void validatePayment_throwsWhenToAccountInactive() {
        toAccount.setStatus(AccountStatus.INACTIVE);
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));
        assertThatThrownBy(() -> paymentService.validatePayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Racun primaoca nije aktivan");
    }

    @Test
    void validatePayment_throwsWhenSameAccount() {
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber(request.getToAccount())).thenReturn(Optional.of(fromAccount));
        assertThatThrownBy(() -> paymentService.validatePayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Racuni moraju biti razliciti");
    }

    @Test
    void validatePayment_throwsWhenFromAccountNotOwnedByClient() {
        Client other = new Client();
        other.setId(777L);
        fromAccount.setClient(other);
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));
        assertThatThrownBy(() -> paymentService.validatePayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Racun ne pripada klijentu");
    }

    @Test
    void validatePayment_throwsWhenInsufficientFunds() {
        fromAccount.setAvailableBalance(new BigDecimal("5.00"));
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));
        assertThatThrownBy(() -> paymentService.validatePayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nedovoljno sredstava");
    }

    @Test
    void validatePayment_throwsWhenDailyLimitExceeded() {
        fromAccount.setDailyLimit(new BigDecimal("50.00"));
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));
        assertThatThrownBy(() -> paymentService.validatePayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prekoracen dnevni limit");
    }

    @Test
    void validatePayment_throwsWhenMonthlyLimitExceeded() {
        fromAccount.setMonthlyLimit(new BigDecimal("70.00"));
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));
        assertThatThrownBy(() -> paymentService.validatePayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prekoracen mesecni limit");
    }

    @Test
    void validatePayment_succeedsForValidRequest() {
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber(request.getToAccount())).thenReturn(Optional.of(toAccount));
        paymentService.validatePayment(request); // no exception
    }

    // ── TEST-interbank-4: validatePayment preflight za INTERBANK toAccount ──
    // Za medjubankarsko placanje (toAccount na udaljenoj banci) primalacev racun
    // NE postoji u nasoj lokalnoj bazi, pa preflight (/request-otp) NE sme da padne
    // na "Racun primaoca ne postoji" — inace klijent nikad ne dobije OTP za
    // interbank uplatu. Validira se SAMO posiljalac + sredstva (bez source-side
    // provizije; FX/proviziju radi Banka B).

    @Test
    void validatePayment_interbankToAccount_doesNotThrowAccountNotExists_TEST_interbank_4() {
        // toAccount je na udaljenoj banci → isLocalAccount(toAccount)=false → interbank.
        when(bankRoutingService.isLocalAccount(request.getToAccount())).thenReturn(false);
        when(accountRepository.findByAccountNumber(request.getFromAccount()))
                .thenReturn(Optional.of(fromAccount));
        // toAccount NIJE u lokalnoj bazi (vraca empty) — pre fix-a bi ovo bacilo
        // "Racun primaoca ne postoji".
        lenient().when(accountRepository.findByAccountNumber(request.getToAccount()))
                .thenReturn(Optional.empty());

        // KLJUCNA invarijanta: preflight NE baca za nepostojeci-lokalno interbank racun.
        paymentService.validatePayment(request);

        // Lokalni toAccount lookup se ne sme ni izvrsiti (interbank grana ga preskace).
        verify(accountRepository, never()).findByAccountNumber(request.getToAccount());
    }

    @Test
    void validatePayment_interbankToAccount_stillEnforcesSenderFunds_TEST_interbank_4() {
        // Interbank grana ne sme da preskoci proveru sredstava posiljaoca.
        when(bankRoutingService.isLocalAccount(request.getToAccount())).thenReturn(false);
        fromAccount.setAvailableBalance(new BigDecimal("5.00"));
        when(accountRepository.findByAccountNumber(request.getFromAccount()))
                .thenReturn(Optional.of(fromAccount));

        assertThatThrownBy(() -> paymentService.validatePayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nedovoljno sredstava");
    }

    // ========== recordAbortedPayment ==========

    @Test
    void recordAbortedPayment_returnsNullForNullRequest() {
        assertThat(paymentService.recordAbortedPayment(null, "otp")).isNull();
    }

    @Test
    void recordAbortedPayment_returnsNullWhenFromAccountNull() {
        request.setFromAccount(null);
        assertThat(paymentService.recordAbortedPayment(request, "otp")).isNull();
    }

    @Test
    void recordAbortedPayment_returnsNullWhenAmountNull() {
        request.setAmount(null);
        assertThat(paymentService.recordAbortedPayment(request, "otp")).isNull();
    }

    @Test
    void recordAbortedPayment_returnsNullWhenFromAccountNotFound() {
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.empty());
        assertThat(paymentService.recordAbortedPayment(request, "otp")).isNull();
    }

    @Test
    void recordAbortedPayment_returnsNullWhenNotAuthenticated() {
        SecurityContextHolder.clearContext();
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        assertThat(paymentService.recordAbortedPayment(request, "otp")).isNull();
    }

    @Test
    void recordAbortedPayment_returnsNullWhenWrongClient() {
        Client other = new Client();
        other.setId(999L);
        fromAccount.setClient(other);
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        assertThat(paymentService.recordAbortedPayment(request, "otp")).isNull();
    }

    @Test
    void recordAbortedPayment_savesAbortedPaymentWithExplicitReason() {
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(77L);
            return p;
        });

        Long id = paymentService.recordAbortedPayment(request, "OTP max retries exceeded");

        assertThat(id).isEqualTo(77L);
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.ABORTED);
        assertThat(captor.getValue().getPurpose()).isEqualTo("OTP max retries exceeded");
    }

    @Test
    void recordAbortedPayment_usesDefaultPurposeWhenReasonNull() {
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(78L);
            return p;
        });

        Long id = paymentService.recordAbortedPayment(request, null);

        assertThat(id).isEqualTo(78L);
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPurpose()).isEqualTo("OTP otkazano");
    }

    @Test
    void recordAbortedPayment_setsNullPaymentCodeWhenMissing() {
        request.setPaymentCode(null);
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(79L);
            return p;
        });

        Long id = paymentService.recordAbortedPayment(request, "expired");

        assertThat(id).isEqualTo(79L);
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPaymentCode()).isNull();
    }

    // ========== BE-PAY-01 audit hook tests ==========

    @Test
    void recordAbortedPayment_firesAuditHookForPaymentAborted() {
        // BE-PAY-01: kad placanje bude abort-ovano (3 OTP fails / OTP isteka),
        // mora se zabeleziti audit log entry sa PAYMENT_ABORTED akcijom.
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(123L);
            return p;
        });

        paymentService.recordAbortedPayment(request, "OTP max retries");

        verify(auditLogService).record(
                eq(client.getId()),
                eq("CLIENT"),
                eq(rs.raf.banka2_bek.audit.model.AuditActionType.PAYMENT_ABORTED),
                argThat(desc -> desc != null && desc.contains("OTP max retries")),
                eq("PAYMENT"),
                eq(123L));
    }

    @Test
    void recordAbortedPayment_auditHookFailureDoesNotFailPayment() {
        // BE-PAY-01: audit log je best-effort — ako baci, payment treba da prodje.
        when(accountRepository.findByAccountNumber(request.getFromAccount())).thenReturn(Optional.of(fromAccount));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(456L);
            return p;
        });
        doThrow(new RuntimeException("audit DB unreachable"))
                .when(auditLogService).record(any(), any(), any(), any(), any(), any());

        Long id = paymentService.recordAbortedPayment(request, "OTP failed");

        // Payment se i dalje uspesno cuva
        assertThat(id).isEqualTo(456L);
    }

    // ===================================================================
    // Quick Approve (Mobile bonus #7) — defensive money-safety guard.
    // ===================================================================

    private Payment buildOwnedPayment(PaymentStatus status) {
        return Payment.builder()
                .id(77L)
                .orderNumber("ORD-77")
                .fromAccount(fromAccount)          // fromAccount.client == client (owner)
                .toAccountNumber("999999999999999999")
                .amount(new BigDecimal("50.00"))
                .fee(BigDecimal.ZERO)
                .currency(eur)
                .paymentCode(PaymentCode.CODE_289.getCode())
                .referenceNumber("REF-77")
                .purpose("QA test")
                .status(status)
                .createdBy(client)
                .createdAt(LocalDateTime.now())     // unutar 5min TTL
                .build();
    }

    @Test
    void quickApprove_alreadyCompletedOwnedPayment_isIdempotentSuccessNoSideEffects() {
        Payment completed = buildOwnedPayment(PaymentStatus.COMPLETED);
        when(paymentRepository.findById(77L)).thenReturn(Optional.of(completed));

        PaymentResponseDto res = paymentService.quickApprove(77L, client.getEmail(), "123456");

        // Idempotent: vraca postojeci COMPLETED payload bez ikakvog novog dispatch-a.
        assertThat(res).isNotNull();
        assertThat(res.getId()).isEqualTo(77L);
        assertThat(res.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        // Nista se ne menja: bez OTP-a, bez save-a, bez notifikacije, bez audit-a.
        verify(otpService, never()).verify(anyString(), anyString());
        verify(paymentRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void quickApprove_nonCompletedPayment_afterOtpOk_throwsNotWired_neverCompletes() {
        Payment pending = buildOwnedPayment(PaymentStatus.PROCESSING);
        when(paymentRepository.findById(77L)).thenReturn(Optional.of(pending));
        // OTP prolazi -> dolazimo do koraka 7 (defensive guard).
        when(otpService.verify(client.getEmail(), "123456"))
                .thenReturn(java.util.Map.of("verified", true, "blocked", false));

        assertThatThrownBy(() -> paymentService.quickApprove(77L, client.getEmail(), "123456"))
                .isInstanceOf(QuickApproveSettlementNotWiredException.class)
                .hasMessageContaining("Phase-2 FCM");

        // KRITICNO: payment NIJE markiran COMPLETED, nema lazne completion-e.
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        verify(paymentRepository, never()).save(any());
        // Nema "placanje odobreno" notifikacije/audita za ne-settle-ovan payment.
        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void quickApprove_processingPayment_afterOtpOk_throwsNotWired_neverCompletes() {
        // 2PC inter-bank payment je PROCESSING — quickApprove ne sme da ga finalizuje.
        Payment processing = buildOwnedPayment(PaymentStatus.PROCESSING);
        when(paymentRepository.findById(77L)).thenReturn(Optional.of(processing));
        when(otpService.verify(client.getEmail(), "123456"))
                .thenReturn(java.util.Map.of("verified", true, "blocked", false));

        assertThatThrownBy(() -> paymentService.quickApprove(77L, client.getEmail(), "123456"))
                .isInstanceOf(QuickApproveSettlementNotWiredException.class);

        assertThat(processing.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void quickApprove_paymentNotFound_throws404() {
        when(paymentRepository.findById(77L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> paymentService.quickApprove(77L, client.getEmail(), "123456"))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void quickApprove_notOwnedByUser_throwsNotOwned() {
        Client other = new Client();
        other.setId(999L);
        Account otherAcc = baseAccount(5L, "555555555555555555", other, eur, new BigDecimal("100.00"));
        Payment notMine = Payment.builder()
                .id(77L).fromAccount(otherAcc).toAccountNumber("1").amount(new BigDecimal("10.00"))
                .currency(eur).status(PaymentStatus.PROCESSING).createdBy(other)
                .createdAt(LocalDateTime.now()).build();
        when(paymentRepository.findById(77L)).thenReturn(Optional.of(notMine));

        assertThatThrownBy(() -> paymentService.quickApprove(77L, client.getEmail(), "123456"))
                .isInstanceOf(PaymentNotOwnedException.class);
        verify(otpService, never()).verify(anyString(), anyString());
    }

    @Test
    void quickApprove_alreadyAborted_throwsFinalized409() {
        Payment aborted = buildOwnedPayment(PaymentStatus.ABORTED);
        when(paymentRepository.findById(77L)).thenReturn(Optional.of(aborted));

        assertThatThrownBy(() -> paymentService.quickApprove(77L, client.getEmail(), "123456"))
                .isInstanceOf(PaymentAlreadyFinalizedException.class);
        verify(otpService, never()).verify(anyString(), anyString());
    }

    @Test
    void quickApprove_ttlExpired_throwsTimeout() {
        Payment stale = buildOwnedPayment(PaymentStatus.PROCESSING);
        stale.setCreatedAt(LocalDateTime.now().minusMinutes(10)); // van 5min TTL-a
        when(paymentRepository.findById(77L)).thenReturn(Optional.of(stale));

        assertThatThrownBy(() -> paymentService.quickApprove(77L, client.getEmail(), "123456"))
                .isInstanceOf(PaymentTimeoutException.class);
        verify(otpService, never()).verify(anyString(), anyString());
    }

    @Test
    void quickApprove_wrongOtp_throwsOtpInvalid_neverReachesGuard() {
        Payment pending = buildOwnedPayment(PaymentStatus.PROCESSING);
        when(paymentRepository.findById(77L)).thenReturn(Optional.of(pending));
        when(otpService.verify(client.getEmail(), "000000"))
                .thenReturn(java.util.Map.of("verified", false, "blocked", false, "message", "Pogresan kod."));

        assertThatThrownBy(() -> paymentService.quickApprove(77L, client.getEmail(), "000000"))
                .isInstanceOf(OtpInvalidException.class);
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void quickApprove_otpBlocked_throwsOtpLocked() {
        Payment pending = buildOwnedPayment(PaymentStatus.PROCESSING);
        when(paymentRepository.findById(77L)).thenReturn(Optional.of(pending));
        when(otpService.verify(client.getEmail(), "000000"))
                .thenReturn(java.util.Map.of("verified", false, "blocked", true, "message", "Zakljucano."));

        assertThatThrownBy(() -> paymentService.quickApprove(77L, client.getEmail(), "000000"))
                .isInstanceOf(OtpLockedException.class);
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        verify(paymentRepository, never()).save(any());
    }

    private void authenticateAs(String email) {
        org.springframework.security.core.userdetails.User principal =
                new org.springframework.security.core.userdetails.User(
                        email,
                        "x",
                        List.of(new SimpleGrantedAuthority("ROLE_CLIENT"))
                );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    private Account baseAccount(Long id, String accountNumber, Client owner, Currency currency, BigDecimal balance) {
        return Account.builder()
                .id(id)
                .accountNumber(accountNumber)
                .currency(currency)
                .status(AccountStatus.ACTIVE)
                .client(owner)
                .balance(balance)
                .availableBalance(balance)
                .dailyLimit(new BigDecimal("5000.00"))
                .monthlyLimit(new BigDecimal("20000.00"))
                .dailySpending(BigDecimal.ZERO)
                .monthlySpending(BigDecimal.ZERO)
                .build();
    }

    private DataIntegrityViolationException uniqueViolation() {
        return new DataIntegrityViolationException(
                "duplicate key",
                new RuntimeException("Duplicate entry for key 'uk_payments_order_number'")
        );
    }

    private List<ExchangeRateDto> defaultRates() {
        return List.of(
                new ExchangeRateDto("RSD", 1.0),
                new ExchangeRateDto("EUR", 0.008532423208191127),
                new ExchangeRateDto("USD", 0.009216589861751152),
                new ExchangeRateDto("CHF", 0.008143322475570033),
                new ExchangeRateDto("GBP", 0.00727802037845706),
                new ExchangeRateDto("JPY", 1.36986301369863),
                new ExchangeRateDto("CAD", 0.012484394506866417),
                new ExchangeRateDto("AUD", 0.013966480446927373)
        );
    }

}
