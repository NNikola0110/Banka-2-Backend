package rs.raf.banka2_bek.payment.service.implementation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.exchange.ExchangeService;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.interbank.protocol.Asset;
import rs.raf.banka2_bek.interbank.protocol.CurrencyCode;
import rs.raf.banka2_bek.interbank.protocol.MonetaryAsset;
import rs.raf.banka2_bek.interbank.protocol.Posting;
import rs.raf.banka2_bek.interbank.protocol.Transaction;
import rs.raf.banka2_bek.interbank.protocol.TxAccount;
import rs.raf.banka2_bek.interbank.repository.InterbankTransactionRepository;
import rs.raf.banka2_bek.interbank.service.BankRoutingService;
import rs.raf.banka2_bek.interbank.service.InterbankPaymentAsyncService;
import rs.raf.banka2_bek.interbank.service.TransactionExecutorService;
import rs.raf.banka2_bek.otp.service.OtpService;
import rs.raf.banka2_bek.payment.dto.CreatePaymentRequestDto;
import rs.raf.banka2_bek.payment.dto.PaymentDirection;
import rs.raf.banka2_bek.payment.dto.PaymentListItemDto;
import rs.raf.banka2_bek.payment.dto.PaymentResponseDto;
import rs.raf.banka2_bek.payment.exception.OtpInvalidException;
import rs.raf.banka2_bek.payment.exception.OtpLockedException;
import rs.raf.banka2_bek.payment.exception.PaymentAlreadyFinalizedException;
import rs.raf.banka2_bek.payment.exception.PaymentNotFoundException;
import rs.raf.banka2_bek.payment.exception.PaymentNotOwnedException;
import rs.raf.banka2_bek.payment.exception.PaymentTimeoutException;
import rs.raf.banka2_bek.payment.exception.QuickApproveSettlementNotWiredException;
import rs.raf.banka2_bek.payment.model.Payment;
import rs.raf.banka2_bek.payment.model.PaymentStatus;
import rs.raf.banka2_bek.payment.repository.PaymentAccountRepository;
import rs.raf.banka2_bek.payment.repository.PaymentRepository;
import rs.raf.banka2_bek.payment.service.PaymentReceiptPdfGenerator;
import rs.raf.banka2_bek.payment.service.PaymentService;
import rs.raf.banka2_bek.notification.NotificationPublisher;
import rs.raf.banka2_bek.notification.model.NotificationType;
import rs.raf.banka2_bek.notification.service.NotificationService;
import rs.raf.banka2_bek.transaction.dto.TransactionListItemDto;
import rs.raf.banka2_bek.transaction.dto.TransactionResponseDto;
import rs.raf.banka2_bek.transaction.dto.TransactionType;
import rs.raf.banka2_bek.transaction.service.TransactionService;
import rs.raf.banka2_bek.audit.model.AuditActionType;
import rs.raf.banka2_bek.audit.service.AuditLogService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@lombok.extern.slf4j.Slf4j
@Service
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentAccountRepository paymentAccountRepository;
    private final AccountRepository accountRepository;
    private final ClientRepository clientRepository;
    private final TransactionService transactionService;
    private final PaymentReceiptPdfGenerator paymentReceiptPdfGenerator;
    private final ExchangeService exchangeService;
    private final NotificationPublisher notificationPublisher;
    private final BankRoutingService bankRoutingService;
    private final TransactionExecutorService transactionExecutorService;
    private final InterbankPaymentAsyncService interbankPaymentAsyncService;
    private final InterbankTransactionRepository interbankTransactionRepository;
    private final String bankRegistrationNumber;
    private final NotificationService notificationService;
    // BE-PAY-01: audit log za payment lifecycle. Optional via @Autowired(required=false)
    // bi bilo bolje, ali polje je final pa rezolvujemo kroz field setter kasnije.
    private final AuditLogService auditLogService;

    /**
     * TODO_final Mobile bonus #7 — Quick Approve OTP gating. Setter injection
     * koristen umesto konstruktora da ne bi razbio sve postojece testove
     * (15-parametar ctor pattern fixiran). Spring injektuje preko field-level
     * autowire, Mockito test mock-ing radi preko setOtpService().
     */
    @org.springframework.beans.factory.annotation.Autowired
    private OtpService otpService;

    /**
     * R7 observability: inkrementira {@code banka2_payments_executed_total} na svako
     * realizovano unutarbankarsko placanje. Field injection (kao {@code otpService})
     * da se ne dira 15-parametar konstruktor; null-guard za Mockito unit-testove koji
     * konstruisu servis bez Spring konteksta.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private rs.raf.banka2_bek.monitoring.BusinessMetrics businessMetrics;

    private static final int ORDER_NUMBER_MAX_RETRIES = 5;
    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.005"); // 0.5%
    /** TODO_final Mobile bonus #7 — Quick Approve deep-link expiry (5 minuta od kreiranja payment-a). */
    private static final java.time.Duration QUICK_APPROVE_TTL = java.time.Duration.ofMinutes(5);

    public PaymentServiceImpl(PaymentRepository paymentRepository,
                              PaymentAccountRepository paymentAccountRepository,
                              AccountRepository accountRepository,
                              ClientRepository clientRepository,
                              TransactionService transactionService,
                              PaymentReceiptPdfGenerator paymentReceiptPdfGenerator,
                              ExchangeService exchangeService,
                              NotificationPublisher notificationPublisher,
                              BankRoutingService bankRoutingService,
                              TransactionExecutorService transactionExecutorService,
                              InterbankPaymentAsyncService interbankPaymentAsyncService,
                              InterbankTransactionRepository interbankTransactionRepository,
                              @Value("${bank.registration-number}") String bankRegistrationNumber,
                              NotificationService notificationService,
                              AuditLogService auditLogService) {
        this.paymentRepository = paymentRepository;
        this.paymentAccountRepository = paymentAccountRepository;
        this.accountRepository = accountRepository;
        this.clientRepository = clientRepository;
        this.transactionService = transactionService;
        this.paymentReceiptPdfGenerator = paymentReceiptPdfGenerator;
        this.exchangeService = exchangeService;
        this.notificationPublisher = notificationPublisher;
        this.bankRoutingService = bankRoutingService;
        this.transactionExecutorService = transactionExecutorService;
        this.interbankPaymentAsyncService = interbankPaymentAsyncService;
        this.interbankTransactionRepository = interbankTransactionRepository;
        this.bankRegistrationNumber = bankRegistrationNumber;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
    }

    /**
     * BE-PAY-01: best-effort audit hook. Ne baca exception ako AuditLogService
     * fails — auditing je best-effort i ne sme da fail-uje payment flow.
     * AuditLogService.record je @Transactional(REQUIRES_NEW), pa njegova greska
     * ne pravi rollback nase pozivajuce transakcije.
     */
    private void recordAuditSafe(Long actorId, String actorType, AuditActionType action,
                                 String description, String targetType, Long targetId) {
        try {
            auditLogService.record(actorId, actorType, action, description, targetType, targetId);
        } catch (Exception e) {
            log.warn("Audit log fail (best-effort) action={} target={}/{}: {}",
                    action, targetType, targetId, e.getMessage());
        }
    }

    /**
     * PAYMENT FLOW (od nule):
     *
     * 1. Validacija (racuni, vlasnistvo, status)
     * 2. Izracunaj proviziju (0.5% za cross-currency, 0 za same-currency)
     * 3. Proveri da klijent ima AMOUNT + PROVIZIJA na racunu
     * 4. Proveri dnevni/mesecni limit
     * 5. Za cross-currency: konvertuj preko banke
     * 6. Skini AMOUNT + PROVIZIJA od klijenta (jedan poziv, jedan iznos)
     * 7. Dodaj AMOUNT primaocu
     * 8. Dodaj AMOUNT + PROVIZIJA banci (za cross-currency) ili samo PROVIZIJU (za same-currency)
     * 9. Sacuvaj payment, kreiraj transakcije, posalji email
     *
     * Za medjubankarska placanja (toAccount prefix != nas routing number):
     * koraci 1 i 3 se rade lokalno, a prenos se delegira TransactionExecutorService
     * (2PC protokol §2.8) koji radi asinhrono.
     */
    /**
     * P2-concurrency-locks-1 (R3-1581): zakljucava dva LOKALNA racuna pesimisticki u
     * KANONSKOM redosledu (po account-number-u) i vraca ih u (from, to) orijentaciji.
     *
     * <p>Bez kanonskog redosleda, paralelno placanje A→B i B→A bi zakljucalo racune
     * u suprotnim redosledima ((from=A, to=B) vs (from=B, to=A)) → klasican ABBA DB
     * deadlock. Sortiranje po account-number-u garantuje da SVE niti uzimaju lock-ove
     * u istom globalnom redosledu (lower-number prvi), pa deadlock ne moze nastati.
     * Mirror-uje {@code InternalFundsService.transfer} (koji sortira po ID-u jer prima
     * ID-eve; ovde sortiramo po account-number-u jer to imamo iz zahteva — semanticki
     * isto, oba su stabilni unikatni kljucevi).</p>
     *
     * @return niz {@code [fromAccount, toAccount]} — oba pesimisticki zakljucana.
     */
    private Account[] lockTwoAccountsCanonically(String fromAccountNumber, String toAccountNumber) {
        String firstNum = fromAccountNumber.compareTo(toAccountNumber) <= 0 ? fromAccountNumber : toAccountNumber;
        String secondNum = firstNum.equals(fromAccountNumber) ? toAccountNumber : fromAccountNumber;

        Account first = paymentAccountRepository.findForUpdateByAccountNumber(firstNum)
                .orElseThrow(() -> new IllegalArgumentException(
                        firstNum.equals(fromAccountNumber)
                                ? "Racun posiljaoca ne postoji." : "Racun primaoca ne postoji."));
        Account second = paymentAccountRepository.findForUpdateByAccountNumber(secondNum)
                .orElseThrow(() -> new IllegalArgumentException(
                        secondNum.equals(fromAccountNumber)
                                ? "Racun posiljaoca ne postoji." : "Racun primaoca ne postoji."));

        Account fromAccount = first.getAccountNumber().equals(fromAccountNumber) ? first : second;
        Account toAccount = first.getAccountNumber().equals(toAccountNumber) ? first : second;
        return new Account[]{fromAccount, toAccount};
    }

    @Override
    @Transactional
    public PaymentResponseDto createPayment(CreatePaymentRequestDto request) {
        // ===== FORK odluka (cist routing-string, BEZ locka) =====
        // P2-concurrency-locks-1 (R3-1581): fork koristi isLocalAccount(toAccountNumber)
        // koji ne dodiruje DB — tako da pre uzimanja ijednog lock-a znamo flow i mozemo
        // da zakljucamo LOKALNI flow u KANONSKOM redosledu (deadlock-free).
        boolean interbank = !bankRoutingService.isLocalAccount(request.getToAccount());

        Account fromAccount;
        Account toAccount = null;
        if (interbank) {
            // Inter-bank: samo JEDAN lokalni racun (posiljalac) → nema ABBA, lock direktno.
            fromAccount = paymentAccountRepository.findForUpdateByAccountNumber(request.getFromAccount())
                    .orElseThrow(() -> new IllegalArgumentException("Racun posiljaoca ne postoji."));
        } else {
            // Lokalni: OBA racuna se zakljucavaju u KANONSKOM redosledu (nizi
            // account-number prvi) — dve konkurentne reverse niti (A→B i B→A) uzimaju
            // lock-ove u ISTOM globalnom redosledu, pa ABBA DB deadlock ne moze nastati.
            if (request.getFromAccount().equals(request.getToAccount()))
                throw new IllegalArgumentException("Racuni moraju biti razliciti.");
            Account[] locked = lockTwoAccountsCanonically(request.getFromAccount(), request.getToAccount());
            fromAccount = locked[0];
            toAccount = locked[1];
        }

        // ===== VALIDACIJA posiljaoca (zajednicki za oba flow-a) =====
        if (fromAccount.getStatus() != AccountStatus.ACTIVE)
            throw new IllegalArgumentException("Racun posiljaoca nije aktivan.");

        Client client = getAuthenticatedClient();
        if (fromAccount.getClient() == null || !fromAccount.getClient().getId().equals(client.getId()))
            throw new IllegalArgumentException("Racun ne pripada klijentu.");

        if (interbank) {
            return createInterbankPayment(request, client, fromAccount);
        }

        // ===== LOKALNI FLOW =====
        if (toAccount.getStatus() != AccountStatus.ACTIVE)
            throw new IllegalArgumentException("Racun primaoca nije aktivan.");
        if (fromAccount.getId().equals(toAccount.getId()))
            throw new IllegalArgumentException("Racuni moraju biti razliciti.");

        // ===== 2. PROVIZIJA =====
        BigDecimal amount = request.getAmount();
        boolean isCrossCurrency = !fromAccount.getCurrency().getId().equals(toAccount.getCurrency().getId());
        BigDecimal fee = isCrossCurrency
                ? amount.multiply(COMMISSION_RATE).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalFromClient = amount.add(fee); // ovo se skida od klijenta

        // ===== 3. PROVERA SREDSTAVA =====
        if (fromAccount.getAvailableBalance().compareTo(totalFromClient) < 0)
            throw new IllegalArgumentException("Nedovoljno sredstava na racunu. Potrebno: " + totalFromClient);

        // ===== 4. LIMITI =====
        // R2 1428: limit/potrosnja se racunaju na CEO iznos skinut sa racuna (amount+fee).
        // Spec C2: "Dnevna/Mesecna potrosnja = ukupan iznos POTROSEN" = sav novac koji
        // napusti racun klijenta = totalFromClient (amount + provizija). Provera sredstava
        // (korak 3) vec koristi totalFromClient; limit i potrosnja moraju biti dosledni —
        // inace bi se provizija "sakrila" od limita (klijent prelazi limit za iznos fee-ja).
        if (fromAccount.getDailyLimit() != null && fromAccount.getDailyLimit().compareTo(BigDecimal.ZERO) > 0
                && fromAccount.getDailySpending().add(totalFromClient).compareTo(fromAccount.getDailyLimit()) > 0)
            throw new IllegalArgumentException("Prekoracen dnevni limit.");
        if (fromAccount.getMonthlyLimit() != null && fromAccount.getMonthlyLimit().compareTo(BigDecimal.ZERO) > 0
                && fromAccount.getMonthlySpending().add(totalFromClient).compareTo(fromAccount.getMonthlyLimit()) > 0)
            throw new IllegalArgumentException("Prekoracen mesecni limit.");

        // ===== 5. CROSS-CURRENCY KONVERZIJA =====
        BigDecimal creditedAmount = amount; // koliko primalac dobija
        Account bankFromAccountRef = null;
        Account bankToAccountRef = null;
        if (isCrossCurrency) {
            String fromCurr = fromAccount.getCurrency().getCode();
            String toCurr = toAccount.getCurrency().getCode();

            // P0-B4: BigDecimal FX put — conservation-exact (nema double round-greske u knjizi).
            ExchangeService.FxConversionResult fx = exchangeService.calculateCrossExact(
                    amount, fromCurr, toCurr);
            creditedAmount = fx.convertedAmount();

            // Bankin racun za target valutu mora imati dovoljno
            Account bankToAccount = accountRepository.findBankAccountForUpdateByCurrency(bankRegistrationNumber, toCurr)
                    .orElseThrow(() -> new RuntimeException("Banka nema racun za " + toCurr));
            if (bankToAccount.getAvailableBalance().compareTo(creditedAmount) < 0)
                throw new RuntimeException("Banka nema dovoljno " + toCurr);

            // Banka placa target valutu primaocu
            bankToAccount.setBalance(bankToAccount.getBalance().subtract(creditedAmount));
            bankToAccount.setAvailableBalance(bankToAccount.getAvailableBalance().subtract(creditedAmount));
            bankToAccountRef = accountRepository.save(bankToAccount);

            // Banka prima source valutu (amount + provizija) od klijenta
            Account bankFromAccount = accountRepository.findBankAccountForUpdateByCurrency(bankRegistrationNumber, fromCurr)
                    .orElseThrow(() -> new RuntimeException("Banka nema racun za " + fromCurr));
            bankFromAccount.setBalance(bankFromAccount.getBalance().add(totalFromClient));
            bankFromAccount.setAvailableBalance(bankFromAccount.getAvailableBalance().add(totalFromClient));
            bankFromAccountRef = accountRepository.save(bankFromAccount);
        }

        // ===== 6. SKINI OD KLIJENTA (amount + fee, JEDNOM) =====
        // R2 1428: potrosnja prati STVARNO skinut iznos (amount+fee), dosledno sa
        // proverom sredstava (korak 3) i limita (korak 4). Same-currency: fee=0 pa nepromenjeno.
        fromAccount.setBalance(fromAccount.getBalance().subtract(totalFromClient));
        fromAccount.setAvailableBalance(fromAccount.getAvailableBalance().subtract(totalFromClient));
        fromAccount.setDailySpending(fromAccount.getDailySpending().add(totalFromClient));
        fromAccount.setMonthlySpending(fromAccount.getMonthlySpending().add(totalFromClient));

        // ===== 7. DODAJ PRIMAOCU =====
        toAccount.setBalance(toAccount.getBalance().add(creditedAmount));
        toAccount.setAvailableBalance(toAccount.getAvailableBalance().add(creditedAmount));

        // ===== 8. SACUVAJ PAYMENT =====
        String paymentCode = request.getPaymentCode().getCode();
        Payment payment = Payment.builder()
                .fromAccount(fromAccount)
                .toAccountNumber(request.getToAccount())
                .amount(amount)
                .fee(fee)
                .currency(fromAccount.getCurrency())
                .recipientName(request.getRecipientName())
                .paymentCode(paymentCode)
                .referenceNumber(request.getReferenceNumber())
                .purpose(request.getDescription())
                .status(PaymentStatus.COMPLETED)
                .createdBy(client)
                .build();

        Payment savedPayment = null;
        for (int attempt = 1; attempt <= ORDER_NUMBER_MAX_RETRIES; attempt++) {
            try {
                payment.setOrderNumber(generateOrderNumber());
                savedPayment = paymentRepository.saveAndFlush(payment);
                break;
            } catch (DataIntegrityViolationException ex) {
                String msg = ex.getMostSpecificCause().getMessage();
                if (msg == null) throw ex;
                String lower = msg.toLowerCase();
                if (!(lower.contains("order_number") || lower.contains("uk") || lower.contains("unique")))
                    throw ex;
            }
        }
        if (savedPayment == null)
            throw new IllegalStateException("Generisanje broja placanja nije uspelo.");

        // ===== 9. TRANSAKCIJE + EMAIL =====
        // T2-013: za cross-currency, persistiraj 3-fazni lanac (6 transaction redova)
        // za audit trail (spec C2 §255-258). Za same-currency, 2 reda (debit + credit).
        if (isCrossCurrency && bankFromAccountRef != null && bankToAccountRef != null) {
            transactionService.recordCrossCurrencyPaymentSettlement(
                    savedPayment, toAccount, bankFromAccountRef, bankToAccountRef,
                    client, totalFromClient, creditedAmount);
        } else {
            transactionService.recordPaymentSettlement(savedPayment, toAccount, client, creditedAmount);
        }

        // R7 observability: realizovano placanje (banka2_payments_executed_total).
        if (businessMetrics != null) {
            businessMetrics.recordPaymentExecuted();
        }

        try {
            notificationPublisher.sendPaymentConfirmationMail(
                    client.getEmail(), amount,
                    fromAccount.getCurrency() != null ? fromAccount.getCurrency().getCode() : null,
                    fromAccount.getAccountNumber(), request.getToAccount(),
                    savedPayment.getCreatedAt() != null ? savedPayment.getCreatedAt().toLocalDate() : java.time.LocalDate.now(),
                    "COMPLETED");
        } catch (Exception e) {
            log.warn("Failed to send payment confirmation email: {}", e.getMessage());
        }

        try {
            notificationService.notify(
                    client.getId(),
                    "CLIENT",
                    NotificationType.PAYMENT,
                    "Plaćanje izvršeno",
                    "Vaše plaćanje od " + amount + " " + (fromAccount.getCurrency() != null ? fromAccount.getCurrency().getCode() : "") + " je uspešno izvršeno.",
                    "PAYMENT",
                    savedPayment.getId()
            );
        } catch (Exception e) {
            log.warn("Failed to send payment notification: {}", e.getMessage());
        }

        // BE-PAY-01: audit hook za uspesno placanje
        String currencyCode = fromAccount.getCurrency() != null ? fromAccount.getCurrency().getCode() : "";
        recordAuditSafe(
                client.getId(), "CLIENT",
                AuditActionType.PAYMENT_CREATED,
                "Payment " + amount + " " + currencyCode + " "
                        + fromAccount.getAccountNumber() + " -> " + request.getToAccount(),
                "PAYMENT", savedPayment.getId());

        return toResponse(savedPayment, client.getId(), null);
    }

    /**
     * Interbank payment via 2PC protocol (§2.8).
     *
     * Saves a PROCESSING payment record immediately, then registers an after-commit
     * hook that triggers the async 2PC on the interbank thread pool. The caller
     * (createPayment) is already inside @Transactional, so the Payment row is
     * committed before executeAsync runs.
     */
    private PaymentResponseDto createInterbankPayment(CreatePaymentRequestDto request,
                                                       Client client,
                                                       Account fromAccount) {
        BigDecimal amount = request.getAmount();

        // ===== 1374: SINHRONA PROVERA SREDSTAVA (posiljalac) =====
        // Pre fix-a interbank flow je proveravao SAMO limite, ne i raspolozivo stanje.
        // Klijent bez sredstava bi dobio HTTP 200 + status PREPARING, a tek async 2PC
        // (reserveMonas u prepareLocal) bi pao na INSUFFICIENT_ASSET → placanje zavrsi
        // REJECTED bez sinhrone povratne informacije korisniku. Same-bank flow
        // (createPayment) i validatePayment vec rade ovu proveru — interbank je bio
        // jedina rupa. fromAccount je vec pessimistic-locked (findForUpdate u
        // createPayment), pa je provera konzistentna sa rezervacijom koja sledi.
        // Wire iznos je u izvornoj valuti racuna bez source-side provizije
        // (Payment.fee=ZERO; cross-currency FX/proviziju radi Banka B), pa je
        // potrebno tacno 'amount' raspolozivo.
        if (fromAccount.getAvailableBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException(
                    "Nedovoljno sredstava na racunu. Potrebno: " + amount
                            + " " + (fromAccount.getCurrency() != null
                            ? fromAccount.getCurrency().getCode() : ""));
        }

        // ===== LIMITI (posiljalac) =====
        if (fromAccount.getDailyLimit() != null && fromAccount.getDailyLimit().compareTo(BigDecimal.ZERO) > 0
                && fromAccount.getDailySpending().add(amount).compareTo(fromAccount.getDailyLimit()) > 0)
            throw new IllegalArgumentException("Prekoracen dnevni limit.");
        if (fromAccount.getMonthlyLimit() != null && fromAccount.getMonthlyLimit().compareTo(BigDecimal.ZERO) > 0
                && fromAccount.getMonthlySpending().add(amount).compareTo(fromAccount.getMonthlyLimit()) > 0)
            throw new IllegalArgumentException("Prekoracen mesecni limit.");

        String currencyCode = fromAccount.getCurrency() != null
                ? fromAccount.getCurrency().getCode()
                : "RSD";
        CurrencyCode ccy;
        try {
            ccy = CurrencyCode.valueOf(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Valuta racuna nije podrzana u medjubankarskom protokolu: " + currencyCode);
        }

        Asset monetaryAsset = new Asset.Monas(new MonetaryAsset(ccy));

        // Build balanced postings: sender (negative = credit) + receiver (positive = debit)
        List<Posting> postings = List.of(
                new Posting(new TxAccount.Account(fromAccount.getAccountNumber()), amount.negate(), monetaryAsset),
                new Posting(new TxAccount.Account(request.getToAccount()), amount, monetaryAsset)
        );

        Transaction tx = transactionExecutorService.formTransaction(
                postings,
                request.getDescription(),
                request.getReferenceNumber(),
                request.getPaymentCode().getCode(),
                request.getDescription()
        );

        String paymentCode = request.getPaymentCode().getCode();
        Payment payment = Payment.builder()
                .fromAccount(fromAccount)
                .toAccountNumber(request.getToAccount())
                .amount(amount)
                .fee(BigDecimal.ZERO)
                .currency(fromAccount.getCurrency())
                .recipientName(request.getRecipientName())
                .paymentCode(paymentCode)
                .referenceNumber(request.getReferenceNumber())
                .purpose(request.getDescription())
                .status(PaymentStatus.PROCESSING)
                .createdBy(client)
                .interbankTxIdString(tx.transactionId().id())
                .interbankTxRoutingNumber(tx.transactionId().routingNumber())
                .build();

        Payment savedPayment = null;
        for (int attempt = 1; attempt <= ORDER_NUMBER_MAX_RETRIES; attempt++) {
            try {
                payment.setOrderNumber(generateOrderNumber());
                savedPayment = paymentRepository.saveAndFlush(payment);
                break;
            } catch (DataIntegrityViolationException ex) {
                String msg = ex.getMostSpecificCause().getMessage();
                if (msg == null) throw ex;
                String lower = msg.toLowerCase();
                if (!(lower.contains("order_number") || lower.contains("uk") || lower.contains("unique")))
                    throw ex;
            }
        }
        if (savedPayment == null)
            throw new IllegalStateException("Generisanje broja placanja nije uspelo.");

        final Long paymentId = savedPayment.getId();
        final Transaction txFinal = tx;

        // Fire 2PC after this @Transactional commits so the Payment row is visible
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                interbankPaymentAsyncService.executeAsync(paymentId, txFinal);
            }
        });

        return toResponse(savedPayment, client.getId(), "PREPARING");
    }

    @Override
    @Transactional(readOnly = true)
    public void validatePayment(CreatePaymentRequestDto request) {
        // T2-009 fix: preflight pre OTP-a. Read-only, bez side effects, bez OPTIMISTIC LOCK.
        if (request == null) {
            throw new IllegalArgumentException("Podaci o placanju nedostaju.");
        }
        if (request.getFromAccount() == null || request.getFromAccount().isBlank()) {
            throw new IllegalArgumentException("Racun posiljaoca nedostaje.");
        }
        if (request.getToAccount() == null || request.getToAccount().isBlank()) {
            throw new IllegalArgumentException("Racun primaoca nedostaje.");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Iznos mora biti veci od 0.");
        }

        Account fromAccount = accountRepository.findByAccountNumber(request.getFromAccount())
                .orElseThrow(() -> new IllegalArgumentException("Racun posiljaoca ne postoji."));

        // TEST-interbank-4 fix: za medjubankarska placanja (toAccount na udaljenoj banci)
        // primalacev racun NE postoji u nasoj lokalnoj bazi, pa lokalni
        // findByAccountNumber(toAccount) ne sme da bude izvor "Racun primaoca ne postoji"
        // greske. Pre fix-a je preflight (/request-otp) za interbank placanje uvek padao na
        // toj proveri → klijent nikad nije dobio OTP za interbank uplatu. Mirror-uje
        // createPayment/createInterbankPayment fork (validira SAMO posiljaoca + iznos bez
        // source-side provizije; FX/proviziju radi Banka B). isLocalAccount je cist
        // routing-string check (bez DB-ja), isti onaj koji createPayment koristi za fork.
        boolean interbank = !bankRoutingService.isLocalAccount(request.getToAccount());

        Account toAccount = null;
        if (!interbank) {
            toAccount = accountRepository.findByAccountNumber(request.getToAccount())
                    .orElseThrow(() -> new IllegalArgumentException("Racun primaoca ne postoji."));
        }

        if (fromAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new IllegalArgumentException("Racun posiljaoca nije aktivan.");
        }
        if (!interbank) {
            if (toAccount.getStatus() != AccountStatus.ACTIVE) {
                throw new IllegalArgumentException("Racun primaoca nije aktivan.");
            }
            if (fromAccount.getId().equals(toAccount.getId())) {
                throw new IllegalArgumentException("Racuni moraju biti razliciti.");
            }
        }

        Client client = getAuthenticatedClient();
        if (fromAccount.getClient() == null || !fromAccount.getClient().getId().equals(client.getId())) {
            throw new IllegalArgumentException("Racun ne pripada klijentu.");
        }

        BigDecimal amount = request.getAmount();
        // Cross-currency provizija se naplacuje samo za lokalni (same-bank) cross-currency
        // transfer; interbank wire ide u izvornoj valuti racuna bez source-side provizije
        // (createInterbankPayment: Payment.fee=ZERO, FX/proviziju radi Banka B).
        boolean isCrossCurrency = !interbank
                && !fromAccount.getCurrency().getId().equals(toAccount.getCurrency().getId());
        BigDecimal fee = isCrossCurrency
                ? amount.multiply(COMMISSION_RATE).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalFromClient = amount.add(fee);

        if (fromAccount.getAvailableBalance().compareTo(totalFromClient) < 0) {
            throw new IllegalArgumentException(
                    "Nedovoljno sredstava na racunu. Potrebno: " + totalFromClient
                            + " " + fromAccount.getCurrency().getCode());
        }
        if (fromAccount.getDailyLimit() != null && fromAccount.getDailyLimit().compareTo(BigDecimal.ZERO) > 0
                && fromAccount.getDailySpending().add(amount).compareTo(fromAccount.getDailyLimit()) > 0) {
            throw new IllegalArgumentException("Prekoracen dnevni limit.");
        }
        if (fromAccount.getMonthlyLimit() != null && fromAccount.getMonthlyLimit().compareTo(BigDecimal.ZERO) > 0
                && fromAccount.getMonthlySpending().add(amount).compareTo(fromAccount.getMonthlyLimit()) > 0) {
            throw new IllegalArgumentException("Prekoracen mesecni limit.");
        }
    }

    @Override
    public Page<PaymentListItemDto> getPayments(Pageable pageable, LocalDateTime fromDate, LocalDateTime toDate,
            String accountNumber, BigDecimal minAmount, BigDecimal maxAmount, PaymentStatus status) {
        Client client = getOptionalClient();
        if (client == null) return Page.empty(pageable);
        return paymentRepository.findByUserAccountsWithFilters(client.getId(), fromDate, toDate, accountNumber, minAmount, maxAmount, status, pageable)
                .map(p -> toListItem(p, client.getId()));
    }

    @Override
    public PaymentResponseDto getPaymentById(Long paymentId) {
        Client client = getAuthenticatedClient();
        // R1 330: nepostojece placanje je 404, ne 400 (IllegalArgumentException→400).
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new rs.raf.banka2_bek.payment.exception.PaymentNotFoundException(
                        "Placanje nije pronadjeno."));

        // P2-1 IDOR fix: ovaj endpoint je client-only — autentifikovani klijent
        // sme da vidi placanje SAMO ako je strana u njemu (platilac ili primalac).
        // Bez ove provere bilo koji klijent je mogao da procita TUDJE placanje
        // sekvencijalnim Long id-em (puni info disclosure: iznosi, racuni, primalac,
        // poziv na broj, svrha). Throw -> PaymentNotOwnedException = HTTP 403.
        if (!isPartyToPayment(payment, client.getId())) {
            throw new PaymentNotOwnedException("Placanje ne pripada korisniku.");
        }

        String sagaPhase = null;
        if (payment.getInterbankTxIdString() != null && payment.getInterbankTxRoutingNumber() != null) {
            sagaPhase = interbankTransactionRepository
                    .findByTransactionRoutingNumberAndTransactionIdString(
                            payment.getInterbankTxRoutingNumber(), payment.getInterbankTxIdString())
                    .map(ibTx -> ibTx.getStatus().name())
                    .orElse(null);
        }

        return toResponse(payment, client.getId(), sagaPhase);
    }

    @Override
    public byte[] getPaymentReceipt(Long paymentId) {
        Long clientId = getAuthenticatedClient().getId();
        TransactionResponseDto transaction = transactionService.getReceiptTransaction(paymentId, clientId);
        return paymentReceiptPdfGenerator.generate(transaction);
    }

    @Override
    public Page<TransactionListItemDto> getPaymentHistory(Pageable pageable, LocalDateTime fromDate, LocalDateTime toDate,
            BigDecimal minAmount, BigDecimal maxAmount, TransactionType type) {
        return transactionService.getTransactions(pageable, fromDate, toDate, minAmount, maxAmount, type);
    }

    // ===== MAPPERS =====

    private PaymentResponseDto toResponse(Payment p, Long clientId, String sagaPhase) {
        return PaymentResponseDto.builder()
                .id(p.getId()).orderNumber(p.getOrderNumber())
                .fromAccount(p.getFromAccount() != null ? p.getFromAccount().getAccountNumber() : null)
                .toAccount(p.getToAccountNumber()).amount(p.getAmount()).fee(p.getFee())
                .currency(p.getCurrency() != null ? p.getCurrency().getCode() : null)
                .paymentCode(p.getPaymentCode()).referenceNumber(p.getReferenceNumber())
                .description(p.getPurpose()).recipientName(p.getRecipientName())
                .direction(resolveDirection(p, clientId)).status(p.getStatus())
                .createdAt(p.getCreatedAt()).sagaPhase(sagaPhase)
                .build();
    }

    private PaymentListItemDto toListItem(Payment p, Long clientId) {
        return PaymentListItemDto.builder()
                .id(p.getId()).orderNumber(p.getOrderNumber())
                .fromAccount(p.getFromAccount() != null ? p.getFromAccount().getAccountNumber() : null)
                .toAccount(p.getToAccountNumber()).amount(p.getAmount()).fee(p.getFee())
                .currency(p.getCurrency() != null ? p.getCurrency().getCode() : null)
                .recipientName(p.getRecipientName()).description(p.getPurpose())
                .direction(resolveDirection(p, clientId)).status(p.getStatus()).createdAt(p.getCreatedAt())
                .build();
    }

    private PaymentDirection resolveDirection(Payment p, Long clientId) {
        if (p.getFromAccount() == null || p.getFromAccount().getClient() == null) return PaymentDirection.INCOMING;
        return p.getFromAccount().getClient().getId().equals(clientId) ? PaymentDirection.OUTGOING : PaymentDirection.INCOMING;
    }

    /**
     * P2-1 IDOR ownership guard: vraca true ako je {@code clientId} strana u
     * placanju — ili kao platilac (fromAccount pripada klijentu) ili kao primalac
     * (lokalni racun po toAccountNumber pripada klijentu). Inter-bank placanja
     * cija primalacka strana je u drugoj banci nemaju lokalni toAccount, pa za
     * njih vazi samo fromAccount provera.
     */
    private boolean isPartyToPayment(Payment p, Long clientId) {
        if (p.getFromAccount() != null && p.getFromAccount().getClient() != null
                && p.getFromAccount().getClient().getId().equals(clientId)) {
            return true;
        }
        if (p.getToAccountNumber() != null) {
            return accountRepository.findByAccountNumber(p.getToAccountNumber())
                    .map(a -> a.getClient() != null && a.getClient().getId().equals(clientId))
                    .orElse(false);
        }
        return false;
    }

    // ===== AUTH HELPERS =====

    private String getAuthenticatedUsername() {
        // R1-521: nedostatak autentikacije je 401, ne 400 — NotAuthenticatedException
        // mapira na HTTP 401 (IllegalArgumentException je mapirao na 400).
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated())
            throw new rs.raf.banka2_bek.payment.exception.NotAuthenticatedException("Niste prijavljeni.");
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetails ud) return ud.getUsername();
        throw new rs.raf.banka2_bek.payment.exception.NotAuthenticatedException("Niste prijavljeni.");
    }

    private Client getAuthenticatedClient() {
        Client c = getOptionalClient();
        if (c == null) throw new IllegalArgumentException("Klijent nije pronadjen.");
        return c;
    }

    private Client getOptionalClient() {
        try { return clientRepository.findByEmail(getAuthenticatedUsername()).orElse(null); }
        catch (Exception e) {
            log.warn("Failed to resolve client: {}", e.getMessage());
            return null;
        }
    }

    private String generateOrderNumber() {
        return "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * T2-012 audit trail: persistira ABORTED red u `payments` da bi se
     * sacuvao trag kada placanje propada zbog 3 neuspela OTP unosa ili
     * isteka koda. Best-effort: ne baca exception ako fails — klijent
     * vec dobija 403 status.
     */
    @Override
    @Transactional
    public Long recordAbortedPayment(CreatePaymentRequestDto request, String reason) {
        if (request == null || request.getFromAccount() == null || request.getAmount() == null) {
            return null;
        }
        try {
            Account fromAccount = accountRepository.findByAccountNumber(request.getFromAccount()).orElse(null);
            if (fromAccount == null) return null;

            Client client = getOptionalClient();
            if (client == null || fromAccount.getClient() == null
                    || !fromAccount.getClient().getId().equals(client.getId())) {
                return null;
            }

            String paymentCode = request.getPaymentCode() != null ? request.getPaymentCode().getCode() : null;
            String purpose = reason != null && !reason.isBlank()
                    ? reason
                    : "OTP otkazano";
            Payment payment = Payment.builder()
                    .orderNumber(generateOrderNumber())
                    .fromAccount(fromAccount)
                    .toAccountNumber(request.getToAccount() != null ? request.getToAccount() : "N/A")
                    .amount(request.getAmount())
                    .fee(BigDecimal.ZERO)
                    .currency(fromAccount.getCurrency())
                    .recipientName(request.getRecipientName())
                    .paymentCode(paymentCode)
                    .referenceNumber(request.getReferenceNumber())
                    .purpose(purpose)
                    .status(PaymentStatus.ABORTED)
                    .createdBy(client)
                    .build();
            for (int attempt = 1; attempt <= ORDER_NUMBER_MAX_RETRIES; attempt++) {
                try {
                    Payment saved = paymentRepository.saveAndFlush(payment);
                    // BE-PAY-01: audit hook za ABORTED placanje (3 OTP fails ili timeout)
                    recordAuditSafe(
                            client.getId(), "CLIENT",
                            AuditActionType.PAYMENT_ABORTED,
                            "Payment aborted: " + (reason != null ? reason : "unspecified")
                                    + " (amount=" + request.getAmount() + ")",
                            "PAYMENT", saved.getId());
                    return saved.getId();
                } catch (DataIntegrityViolationException ex) {
                    payment.setOrderNumber(generateOrderNumber());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to record ABORTED payment audit trail: {}", e.getMessage());
        }
        return null;
    }

    /**
     * TODO_final Mobile bonus #7 — Quick Approve flow.
     *
     * <p>Korak po korak:
     * <ol>
     *   <li>Load Payment by id (404 ako ne postoji)</li>
     *   <li>Verify ownership (403 ako payment.fromAccount.client ne pripada current user-u)</li>
     *   <li>Idempotency check — ako payment.status = COMPLETED, vrati payload bez novog dispatch-a (200 OK)</li>
     *   <li>Status check — ako status je REJECTED/ABORTED/CANCELLED, 409 Conflict</li>
     *   <li>TTL check — payment.createdAt + 5min &lt; now -&gt; 410 Gone</li>
     *   <li>OTP gate — OtpService.verify; blocked=true -&gt; 423 Locked, verified=false -&gt; 401</li>
     *   <li>Defensive guard — ako payment NIJE vec settle-ovan/COMPLETED, settlement
     *       (debit/credit/FX) NIJE wired (Phase-2 FCM), pa bacamo
     *       {@link QuickApproveSettlementNotWiredException} (501 Not Implemented)
     *       umesto lazne completion-e bez pomeranja novca.</li>
     * </ol>
     * </p>
     *
     * <p><b>Money-safety:</b> jedini "success" izlaz iz ove metode je idempotent
     * branch (korak 3) na vec COMPLETED payment-u — tamo je novac vec pomeren u
     * {@code createPayment}. Hipoteticki ne-settle-ovan payment se eksplicitno
     * odbija; nikad se ne lazno-completira.</p>
     */
    @Override
    @Transactional
    public PaymentResponseDto quickApprove(Long paymentId, String userEmail, String otpCode) {
        // ===== 1. LOAD PAYMENT =====
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Placanje nije pronadjeno."));

        // ===== 2. OWNERSHIP CHECK =====
        Client client = clientRepository.findByEmail(userEmail)
                .orElseThrow(() -> new PaymentNotOwnedException("Klijent nije pronadjen."));
        if (payment.getFromAccount() == null || payment.getFromAccount().getClient() == null
                || !payment.getFromAccount().getClient().getId().equals(client.getId())) {
            throw new PaymentNotOwnedException("Placanje ne pripada korisniku.");
        }

        // ===== 3. IDEMPOTENCY — COMPLETED status je success no-op =====
        // Mobile retry posle network 502: vec smo izvrsili approve, samo vrati payload.
        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            log.info("Quick Approve idempotent: payment {} vec COMPLETED, vracam postojeci payload", paymentId);
            return toResponse(payment, client.getId(), null);
        }

        // ===== 4. STATUS CHECK — finalized failure stanja vracaju 409 =====
        if (payment.getStatus() == PaymentStatus.REJECTED
                || payment.getStatus() == PaymentStatus.ABORTED
                || payment.getStatus() == PaymentStatus.CANCELLED) {
            throw new PaymentAlreadyFinalizedException(
                    "Placanje je vec u " + payment.getStatus() + " stanju i ne moze se odobriti.");
        }

        // ===== 5. TTL CHECK — Mobile deep-link iz FCM-a vazi 5 minuta =====
        if (payment.getCreatedAt() != null
                && payment.getCreatedAt().plus(QUICK_APPROVE_TTL).isBefore(LocalDateTime.now())) {
            throw new PaymentTimeoutException(
                    "Vreme za Quick Approve je isteklo (5 minuta od kreiranja placanja).");
        }

        // ===== 6. OTP GATE =====
        java.util.Map<String, Object> otpResult = otpService.verify(userEmail, otpCode);
        if (!Boolean.TRUE.equals(otpResult.get("verified"))) {
            String message = String.valueOf(otpResult.getOrDefault("message", "Pogresan verifikacioni kod."));
            if (Boolean.TRUE.equals(otpResult.get("blocked"))) {
                // BE-AUTH-01: 3 uzastopnih fail-ova u 5min window — caller vidi 423 Locked.
                // Audit hook za 3-strike scenario preko PAYMENT_ABORTED (vec postoji za web flow).
                recordAuditSafe(
                        client.getId(), "CLIENT",
                        AuditActionType.PAYMENT_ABORTED,
                        "Quick Approve OTP locked (3 fail-a): " + message,
                        "PAYMENT", paymentId);
                throw new OtpLockedException(message);
            }
            throw new OtpInvalidException(message);
        }

        // ===== 7. DISPATCH — defensive guard protiv lazne completion-e =====
        // KRITICNO: payment koji stigne dovde NIJE COMPLETED (idempotent branch u
        // koraku 3 bi ga vec vratio) i NIJE u terminal-failure stanju (korak 4).
        // Dakle on JOS UVEK zahteva stvarni settlement (debit + credit + FX) koji
        // ova metoda NE izvrsava. Pre Phase-2 FCM-a, NIJEDAN code path ne kreira
        // ovakav settle-pending payment (intra-bank createPayment settle-uje inline
        // i postavlja COMPLETED; inter-bank 2PC sam vodi svoj commit), pa je ovaj
        // branch trenutno nedostizan. Ali ako se to promeni, NE SMEMO da lazemo
        // completion-u bez pomeranja novca.
        //
        // Phase-2 FCM MORA ovde da wire-uje pravi settlement (debit, credit, FX,
        // exchange svc, BankaCoreClient); dok se to ne uradi, MORAMO da odbijemo
        // zahtev, a NE da fingiramo uspeh.
        // NAPOMENA: Step 8 (audit PAYMENT_QUICK_APPROVED + in-app notifikacija)
        // je namerno uklonjen zajedno sa lazom completion-om — ne sme da se
        // emituje "placanje odobreno" event kad novac nije pomeren. Phase-2 FCM
        // ce ga vratiti TEK posle pravog settlement-a iznad.
        throw new QuickApproveSettlementNotWiredException(
                "Quick Approve settlement nije implementiran (Phase-2 FCM). "
                        + "Placanje u stanju " + payment.getStatus()
                        + " zahteva stvarni settlement koji jos nije wired-ovan; "
                        + "odbijam zahtev umesto lazne completion-e.");
    }
}
