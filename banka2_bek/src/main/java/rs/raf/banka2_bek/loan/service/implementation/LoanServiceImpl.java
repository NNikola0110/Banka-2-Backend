package rs.raf.banka2_bek.loan.service.implementation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.currency.repository.CurrencyRepository;
import rs.raf.banka2_bek.exchange.CurrencyConversionService;
import rs.raf.banka2_bek.loan.dto.*;
import rs.raf.banka2_bek.loan.model.*;
import rs.raf.banka2_bek.loan.service.LoanService;
import rs.raf.banka2_bek.loan.repository.LoanInstallmentRepository;
import rs.raf.banka2_bek.loan.repository.LoanRepository;
import rs.raf.banka2_bek.loan.repository.LoanRequestRepository;
import org.springframework.security.access.AccessDeniedException;
import rs.raf.banka2_bek.notification.NotificationPublisher;
import rs.raf.banka2_bek.notification.model.NotificationType;
import rs.raf.banka2_bek.notification.service.NotificationService;
import rs.raf.banka2_bek.audit.model.AuditActionType;
import rs.raf.banka2_bek.audit.service.AuditLogService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LoanServiceImpl implements LoanService {

    /**
     * [P2-input-validation-1 / R1 344] Dozvoljeni rokovi otplate (broj rata u
     * mesecima) po tipu kredita — Celina 2 §359-361.
     * Gotovinski/auto/studentski/refinansirajuci: 12/24/36/48/60/72/84.
     * Stambeni (MORTGAGE): 60/120/180/240/300/360.
     * Bez ovoga je BE prihvatao bilo koji pozitivan period (7/13/99/1000).
     */
    private static final Set<Integer> STANDARD_PERIODS =
            Set.of(12, 24, 36, 48, 60, 72, 84);
    private static final Set<Integer> MORTGAGE_PERIODS =
            Set.of(60, 120, 180, 240, 300, 360);

    private static Set<Integer> allowedPeriods(LoanType type) {
        return type == LoanType.MORTGAGE ? MORTGAGE_PERIODS : STANDARD_PERIODS;
    }

    private final LoanRequestRepository loanRequestRepository;
    private final LoanRepository loanRepository;
    private final LoanInstallmentRepository installmentRepository;
    private final AccountRepository accountRepository;
    private final ClientRepository clientRepository;
    private final CurrencyRepository currencyRepository;
    private final NotificationPublisher notificationPublisher;
    private final String bankRegistrationNumber;
    private final NotificationService notificationService;
    // BE-PAY-01: audit hooks za loan lifecycle (approve/reject/early-repay)
    private final AuditLogService auditLogService;
    private final EmployeeRepository employeeRepository;
    // P1-savings-loans-1 (R1-133/R3-1630): konverzija strane valute u RSD za FX-band lookup.
    private final CurrencyConversionService currencyConversionService;

    public LoanServiceImpl(LoanRequestRepository loanRequestRepository,
                           LoanRepository loanRepository,
                           LoanInstallmentRepository installmentRepository,
                           AccountRepository accountRepository,
                           ClientRepository clientRepository,
                           CurrencyRepository currencyRepository,
                           NotificationPublisher notificationPublisher,
                           @Value("${bank.registration-number}") String bankRegistrationNumber,
                           NotificationService notificationService,
                           AuditLogService auditLogService,
                           EmployeeRepository employeeRepository,
                           CurrencyConversionService currencyConversionService) {
        this.loanRequestRepository = loanRequestRepository;
        this.loanRepository = loanRepository;
        this.installmentRepository = installmentRepository;
        this.accountRepository = accountRepository;
        this.clientRepository = clientRepository;
        this.currencyRepository = currencyRepository;
        this.notificationPublisher = notificationPublisher;
        this.bankRegistrationNumber = bankRegistrationNumber;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.employeeRepository = employeeRepository;
        this.currencyConversionService = currencyConversionService;
    }

    /**
     * BE-PAY-01 audit hook: best-effort — ne sme da fail-uje pozivajucu transakciju.
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
     * Resolve trenutno autentifikovanog employee-ja (za approve/reject). Vraca null
     * ako ne moze da resolve-uje — auditing se moze i bez actorId-ja.
     */
    private Long resolveCurrentEmployeeId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getName() != null) {
                return employeeRepository.findByEmail(auth.getName())
                        .map(rs.raf.banka2_bek.employee.model.Employee::getId)
                        .orElse(null);
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    /**
     * P0-B8 N2: privilege-escalation guard. Baca {@link AccessDeniedException}
     * ako je trenutno autentifikovani pozivalac CLIENT (rola/authority
     * ROLE_CLIENT ili CLIENT). Zaposleni (ROLE_EMPLOYEE / ROLE_ADMIN /
     * SUPERVISOR) i nezasticeni interni pozivi (bez autentikacije — npr.
     * scheduler / unit testovi) prolaze. Cilj je da ZATVORI agentic put kojim
     * je klijent kroz Arbitro agenta mogao da odobri/odbije kredit, a da NE
     * promeni ponasanje legitimnog HTTP employee puta.
     */
    private void assertNotClientCaller(String message) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) return; // interni/no-auth — ne diramo
        boolean isClient = auth.getAuthorities().stream().anyMatch(a -> {
            String role = a.getAuthority();
            return "ROLE_CLIENT".equals(role) || "CLIENT".equals(role);
        });
        boolean isEmployeeOrAdmin = auth.getAuthorities().stream().anyMatch(a -> {
            String role = a.getAuthority();
            return "ROLE_ADMIN".equals(role) || "ROLE_EMPLOYEE".equals(role)
                    || "ADMIN".equals(role) || "EMPLOYEE".equals(role) || "SUPERVISOR".equals(role);
        });
        if (isClient && !isEmployeeOrAdmin) {
            throw new AccessDeniedException(message);
        }
    }

    @Override
    @Transactional
    public LoanRequestResponseDto createLoanRequest(LoanRequestDto request, String clientEmail) {
        // ACCEPTED-DEVIATION (user-directed 03.06): zahtev za kredit se podnosi
        // direktno, bez OTP-a. OTP vazi samo za placanja i transfere.

        Client client = clientRepository.findByEmail(clientEmail)
                .orElseThrow(() -> new RuntimeException("Klijent nije pronadjen"));

        Account account = accountRepository.findByAccountNumber(request.getAccountNumber())
                .orElseThrow(() -> new RuntimeException("Racun nije pronadjen"));

        if (account.getClient() == null || !account.getClient().getId().equals(client.getId())) {
            throw new RuntimeException("Racun ne pripada klijentu");
        }

        Currency currency = currencyRepository.findByCode(request.getCurrency())
                .orElseThrow(() -> new RuntimeException("Valuta nije podrzana: " + request.getCurrency()));

        if (!account.getCurrency().getCode().equals(currency.getCode())) {
            throw new RuntimeException("Valuta kredita mora da se poklapa sa valutom racuna");
        }

        // [P2-input-validation-1 / R1 344] validacija perioda otplate protiv
        // dozvoljenog skupa po tipu kredita (Celina 2 §359-361).
        LoanType loanType = LoanType.valueOf(request.getLoanType());
        Set<Integer> allowed = allowedPeriods(loanType);
        if (request.getRepaymentPeriod() == null
                || !allowed.contains(request.getRepaymentPeriod())) {
            throw new IllegalArgumentException(
                    "Rok otplate " + request.getRepaymentPeriod()
                            + " nije dozvoljen za tip " + loanType + ". Dozvoljeni: " + allowed);
        }

        LoanRequest loanRequest = LoanRequest.builder()
                .loanType(loanType)
                .interestType(InterestType.valueOf(request.getInterestType()))
                .amount(request.getAmount())
                .currency(currency)
                .loanPurpose(request.getLoanPurpose())
                .repaymentPeriod(request.getRepaymentPeriod())
                .account(account)
                .client(client)
                .phoneNumber(request.getPhoneNumber())
                .employmentStatus(request.getEmploymentStatus())
                .monthlyIncome(request.getMonthlyIncome())
                .permanentEmployment(request.getPermanentEmployment())
                .employmentPeriod(request.getEmploymentPeriod())
                .build();

        LoanRequestResponseDto response = toRequestResponse(loanRequestRepository.save(loanRequest));

        try {
            notificationPublisher.sendLoanRequestSubmittedMail(
                    client.getEmail(),
                    loanRequest.getLoanType().name(),
                    loanRequest.getAmount(),
                    currency.getCode());
        } catch (Exception e) {
            log.warn("Failed to send loan request notification email", e);
        }

        try {
            notificationService.notify(
                    client.getId(),
                    "CLIENT",
                    NotificationType.LOAN_CREATED,
                    "Zahtev za kredit primljen",
                    "Vaš zahtev za kredit od " + loanRequest.getAmount() + " " + currency.getCode() + " je uspešno podnet i čeka na obradu.",
                    "LOAN_REQUEST",
                    loanRequest.getId()
            );
        } catch (Exception e) {
            log.warn("Failed to send loan created notification: {}", e.getMessage());
        }

        return response;
    }

    @Override
    public Page<LoanRequestResponseDto> getLoanRequests(LoanStatus status, Pageable pageable) {
        if (status != null) {
            return loanRequestRepository.findByStatus(status, pageable).map(this::toRequestResponse);
        }
        return loanRequestRepository.findAll(pageable).map(this::toRequestResponse);
    }

    @Override
    @Transactional
    public LoanResponseDto approveLoanRequest(Long requestId) {
        // P0-B8 N2: approve/reject su EMPLOYEE/ADMIN akcije. HTTP put je zasticen
        // URL-om (/loans/requests/** hasAnyRole ADMIN,EMPLOYEE), ali agentic
        // in-process poziv (ApproveLoanRequestActionHandler) zaobilazi kontroler
        // i URL security. Bez ovog guard-a, klijent je mogao kroz Arbitro agenta
        // da odobri sebi kredit. Guard odbija autentifikovanog CLIENT-a.
        assertNotClientCaller("Odobravanje kredita je dozvoljeno samo zaposlenima.");
        LoanRequest request = loanRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Zahtev za kredit nije pronadjen"));

        if (request.getStatus() != LoanStatus.PENDING) {
            throw new RuntimeException("Zahtev je vec obradjen");
        }

        request.setStatus(LoanStatus.APPROVED);
        loanRequestRepository.save(request);

        BigDecimal nominalRate = getBaseRate(request.getAmount(), request.getCurrency().getCode());
        BigDecimal margin = getMargin(request.getLoanType());
        BigDecimal effectiveRate = nominalRate.add(margin);
        BigDecimal monthlyRate = effectiveRate.divide(BigDecimal.valueOf(1200), 10, RoundingMode.HALF_UP);
        int n = request.getRepaymentPeriod();

        // A = P * r * (1+r)^n / ((1+r)^n - 1)
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        BigDecimal onePlusRn = onePlusR.pow(n, MathContext.DECIMAL128);
        BigDecimal monthlyPayment = request.getAmount()
                .multiply(monthlyRate)
                .multiply(onePlusRn)
                .divide(onePlusRn.subtract(BigDecimal.ONE), 4, RoundingMode.HALF_UP);

        LocalDate startDate = LocalDate.now();
        LocalDate endDate = startDate.plusMonths(n);

        String loanNumber = "LN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Loan loan = Loan.builder()
                .loanNumber(loanNumber)
                .loanType(request.getLoanType())
                .interestType(request.getInterestType())
                .amount(request.getAmount())
                .repaymentPeriod(n)
                .nominalRate(nominalRate)
                .effectiveRate(effectiveRate)
                .monthlyPayment(monthlyPayment)
                .startDate(startDate)
                .endDate(endDate)
                .remainingDebt(request.getAmount())
                .currency(request.getCurrency())
                .status(LoanStatus.ACTIVE)
                .account(request.getAccount())
                .client(request.getClient())
                .loanPurpose(request.getLoanPurpose())
                .build();

        loan = loanRepository.save(loan);

        // Disburse loan: bank pays from its account, client receives funds
        Account account = accountRepository.findForUpdateById(request.getAccount().getId())
                .orElseThrow(() -> new RuntimeException("Racun klijenta nije pronadjen"));

        String currencyCode = request.getCurrency().getCode();
        Account bankAccount = accountRepository.findBankAccountForUpdateByCurrency(bankRegistrationNumber, currencyCode)
                .orElseThrow(() -> new RuntimeException("Bankovski racun za " + currencyCode + " nije pronadjen"));

        if (bankAccount.getAvailableBalance().compareTo(request.getAmount()) < 0) {
            throw new RuntimeException("Banka nema dovoljno sredstava za isplatu kredita");
        }

        // Deduct from bank
        bankAccount.setBalance(bankAccount.getBalance().subtract(request.getAmount()));
        bankAccount.setAvailableBalance(bankAccount.getAvailableBalance().subtract(request.getAmount()));
        accountRepository.save(bankAccount);

        // Credit to client
        account.setBalance(account.getBalance().add(request.getAmount()));
        account.setAvailableBalance(account.getAvailableBalance().add(request.getAmount()));
        accountRepository.save(account);

        // Generate installments with principal/interest breakdown
        BigDecimal remainingPrincipal = request.getAmount();
        for (int i = 1; i <= n; i++) {
            BigDecimal interestPortion = remainingPrincipal.multiply(monthlyRate).setScale(4, RoundingMode.HALF_UP);
            BigDecimal principalPortion = monthlyPayment.subtract(interestPortion);
            if (i == n) {
                principalPortion = remainingPrincipal;
            }
            remainingPrincipal = remainingPrincipal.subtract(principalPortion);

            LoanInstallment installment = LoanInstallment.builder()
                    .loan(loan)
                    .amount(monthlyPayment)
                    .principalAmount(principalPortion)
                    .interestAmount(interestPortion)
                    .interestRate(effectiveRate)
                    .currency(request.getCurrency())
                    .expectedDueDate(startDate.plusMonths(i))
                    .paid(false)
                    .build();
            installmentRepository.save(installment);
        }

        try {
            notificationPublisher.sendLoanApprovedMail(
                    request.getClient().getEmail(),
                    loan.getLoanNumber(),
                    loan.getAmount(),
                    loan.getCurrency().getCode(),
                    loan.getMonthlyPayment(),
                    loan.getStartDate());
        } catch (Exception e) {
            log.warn("Failed to send loan approval notification email", e);
        }

        try {
            notificationService.notify(
                    request.getClient().getId(),
                    "CLIENT",
                    NotificationType.LOAN_APPROVED,
                    "Kredit odobren",
                    "Vaš kredit od " + loan.getAmount() + " " + loan.getCurrency().getCode() + " je odobren i sredstva su preneta na vaš račun.",
                    "LOAN",
                    loan.getId()
            );
        } catch (Exception e) {
            log.warn("Failed to send loan approved notification: {}", e.getMessage());
        }

        // BE-PAY-01: audit hook za loan approval
        Long actorId = resolveCurrentEmployeeId();
        recordAuditSafe(
                actorId != null ? actorId : request.getClient().getId(),
                actorId != null ? "EMPLOYEE" : "CLIENT",
                AuditActionType.LOAN_APPROVED,
                "Loan " + loan.getLoanNumber() + " (" + loan.getAmount() + " "
                        + loan.getCurrency().getCode() + ") approved for client " + request.getClient().getId(),
                "LOAN", loan.getId());

        return toLoanResponse(loan);
    }

    @Override
    @Transactional
    public LoanRequestResponseDto rejectLoanRequest(Long requestId) {
        // P0-B8 N2: vidi approveLoanRequest — reject je takodje EMPLOYEE/ADMIN.
        assertNotClientCaller("Odbijanje kredita je dozvoljeno samo zaposlenima.");
        LoanRequest request = loanRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Zahtev za kredit nije pronadjen"));

        if (request.getStatus() != LoanStatus.PENDING) {
            throw new RuntimeException("Zahtev je vec obradjen");
        }

        request.setStatus(LoanStatus.REJECTED);
        LoanRequestResponseDto response = toRequestResponse(loanRequestRepository.save(request));

        try {
            notificationPublisher.sendLoanRejectedMail(
                    request.getClient().getEmail(),
                    request.getLoanType().name(),
                    request.getAmount(),
                    request.getCurrency().getCode());
        } catch (Exception e) {
            log.warn("Failed to send loan rejection notification email", e);
        }

        // BE-PAY-01: audit hook za loan rejection
        Long actorId = resolveCurrentEmployeeId();
        recordAuditSafe(
                actorId != null ? actorId : request.getClient().getId(),
                actorId != null ? "EMPLOYEE" : "CLIENT",
                AuditActionType.LOAN_REJECTED,
                "Loan request #" + requestId + " (" + request.getAmount() + " "
                        + request.getCurrency().getCode() + ") rejected for client " + request.getClient().getId(),
                "LOAN_REQUEST", requestId);

        return response;
    }

    @Override
    public Page<LoanResponseDto> getMyLoans(String clientEmail, Pageable pageable) {
        Client client = clientRepository.findByEmail(clientEmail).orElse(null);
        if (client == null) return Page.empty(pageable);
        return loanRepository.findByClientId(client.getId(), pageable).map(this::toLoanResponse);
    }

    @Override
    public Page<LoanResponseDto> getAllLoans(LoanType loanType, LoanStatus status, String accountNumber, Pageable pageable) {
        return loanRepository.findWithFilters(loanType, status, accountNumber, pageable).map(this::toLoanResponse);
    }

    @Override
    public LoanResponseDto getLoanById(Long loanId) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new RuntimeException("Kredit nije pronadjen"));
        // P0-B9 N3 (IDOR): bez ove provere bilo koji autentifikovani klijent je
        // mogao da procita TUDJI kredit (iznos, kamata, rate, racun) sekvencijalnim
        // loanId-em. CLIENT sme samo SVOJ kredit; EMPLOYEE/ADMIN bilo koji.
        assertLoanAccessibleByCaller(loan);
        return toLoanResponse(loan);
    }

    @Override
    public List<InstallmentResponseDto> getInstallments(Long loanId) {
        // P0-B9 N3 (IDOR): isti razlog kao getLoanById — rate otkrivaju strukturu
        // tudjeg kredita. Ownership se proverava SAMO za CLIENT pozivaoca (employee/
        // admin i no-auth interni pozivi prolaze nepromenjeni — bez dodatnog
        // loanRepository hita, da postojeci scheduler/unit putevi ostanu netaknuti).
        if (isClientCaller()) {
            Loan loan = loanRepository.findById(loanId).orElse(null);
            Client client = currentClientOrNull();
            // CLIENT mora biti vlasnik; nepostojeci kredit -> 403 (ne otkrivamo postojanje).
            if (client != null && (loan == null
                    || loan.getClient() == null
                    || !loan.getClient().getId().equals(client.getId()))) {
                throw new AccessDeniedException("Kredit ne pripada korisniku.");
            }
        }
        return installmentRepository.findByLoanIdOrderByExpectedDueDateAsc(loanId).stream()
                .map(this::toInstallmentResponse)
                .collect(Collectors.toList());
    }

    /**
     * P0-B9 N3 ownership guard: baca {@link AccessDeniedException} (-&gt; HTTP 403)
     * ako je pozivalac CLIENT a kredit ne pripada njemu. Zaposleni
     * (ROLE_EMPLOYEE/ROLE_ADMIN/SUPERVISOR) i nezasticeni interni pozivi (bez
     * auth konteksta — scheduleri/unit testovi) prolaze.
     */
    private void assertLoanAccessibleByCaller(Loan loan) {
        if (isCallerEmployeeOrAdmin()) return;
        Client client = currentClientOrNull();
        if (client == null) return; // no-auth / interni — ne diramo (B8 obrazac)
        if (loan.getClient() == null || !loan.getClient().getId().equals(client.getId())) {
            throw new AccessDeniedException("Kredit ne pripada korisniku.");
        }
    }

    /**
     * Vraca {@code true} samo kad je autentifikovani pozivalac CLIENT
     * (ROLE_CLIENT/CLIENT) i NIJE employee/admin. No-auth (interni/scheduler/unit)
     * vraca {@code false} — ti putevi ne smeju da plate dodatni loanRepository hit.
     */
    private boolean isClientCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) return false;
        if (isCallerEmployeeOrAdmin()) return false;
        return auth.getAuthorities().stream().anyMatch(a -> {
            String role = a.getAuthority();
            return "ROLE_CLIENT".equals(role) || "CLIENT".equals(role);
        });
    }

    private boolean isCallerEmployeeOrAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) return false;
        return auth.getAuthorities().stream().anyMatch(a -> {
            String role = a.getAuthority();
            return "ROLE_ADMIN".equals(role) || "ROLE_EMPLOYEE".equals(role)
                    || "ADMIN".equals(role) || "EMPLOYEE".equals(role) || "SUPERVISOR".equals(role);
        });
    }

    private Client currentClientOrNull() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || auth.getName() == null) return null;
            return clientRepository.findByEmail(auth.getName()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public List<LoanRequestResponseDto> getMyLoanRequests(String clientEmail) {
        Client client = clientRepository.findByEmail(clientEmail).orElse(null);
        if (client == null) return List.of();
        return loanRequestRepository.findByClientIdOrderByCreatedAtDesc(client.getId()).stream()
                .map(this::toRequestResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public LoanResponseDto earlyRepayment(Long loanId, String clientEmail) {
        // ACCEPTED-DEVIATION (user-directed 03.06): prevremena otplata vise NE zahteva
        // OTP. OTP vazi samo za placanja i transfere; krediti nisu placanja.

        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new RuntimeException("Kredit nije pronadjen"));

        if (loan.getStatus() == LoanStatus.REJECTED || loan.getStatus() == LoanStatus.PENDING) {
            throw new RuntimeException("Kredit nije aktivan");
        }
        if (loan.getStatus() == LoanStatus.PAID || loan.getStatus() == LoanStatus.PAID_OFF) {
            throw new RuntimeException("Kredit je vec otplacen");
        }

        Client client = clientRepository.findByEmail(clientEmail)
                .orElseThrow(() -> new RuntimeException("Klijent nije pronadjen"));

        if (!loan.getClient().getId().equals(client.getId())) {
            throw new RuntimeException("Kredit ne pripada klijentu");
        }

        // Izracunaj ukupan iznos za otplatu: preostali principal + neplacena kamata
        List<LoanInstallment> allInstallments = installmentRepository.findByLoanIdOrderByExpectedDueDateAsc(loanId);
        BigDecimal remainingPrincipal = loan.getRemainingDebt();
        BigDecimal unpaidInterest = allInstallments.stream()
                .filter(i -> !Boolean.TRUE.equals(i.getPaid()))
                .map(i -> i.getInterestAmount() != null ? i.getInterestAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal payoffAmount = remainingPrincipal.add(unpaidInterest);

        if (payoffAmount.compareTo(BigDecimal.ZERO) <= 0) {
            loan.setRemainingDebt(BigDecimal.ZERO);
            loan.setStatus(LoanStatus.PAID_OFF);
            loan.setEndDate(LocalDate.now());
            return toLoanResponse(loanRepository.save(loan));
        }

        Account account = accountRepository.findForUpdateById(loan.getAccount().getId())
                .orElseThrow(() -> new RuntimeException("Racun klijenta nije pronadjen"));

        if (!account.getCurrency().getId().equals(loan.getCurrency().getId())) {
            throw new RuntimeException("Valuta racuna i kredita se razlikuju");
        }

        if (account.getAvailableBalance().compareTo(payoffAmount) < 0) {
            throw new RuntimeException("Nedovoljno sredstava na racunu (potrebno: " +
                    payoffAmount + " = " + remainingPrincipal + " principal + " + unpaidInterest + " kamata)");
        }

        // Deduct from client
        account.setBalance(account.getBalance().subtract(payoffAmount));
        account.setAvailableBalance(account.getAvailableBalance().subtract(payoffAmount));
        accountRepository.save(account);

        // Credit to bank account
        String currencyCode = loan.getCurrency().getCode();
        Account bankAccount = accountRepository.findBankAccountForUpdateByCurrency(bankRegistrationNumber, currencyCode)
                .orElseThrow(() -> new RuntimeException("Bankovski racun za " + currencyCode + " nije pronadjen"));
        bankAccount.setBalance(bankAccount.getBalance().add(payoffAmount));
        bankAccount.setAvailableBalance(bankAccount.getAvailableBalance().add(payoffAmount));
        accountRepository.save(bankAccount);

        LocalDate today = LocalDate.now();
        for (LoanInstallment installment : allInstallments) {
            if (!Boolean.TRUE.equals(installment.getPaid())) {
                installment.setPaid(true);
                installment.setActualDueDate(today);
                installmentRepository.save(installment);
            }
        }

        loan.setRemainingDebt(BigDecimal.ZERO);
        loan.setStatus(LoanStatus.PAID_OFF);
        loan.setEndDate(today);
        loanRepository.save(loan);

        // BE-PAY-01: audit hook za early repayment
        recordAuditSafe(
                client.getId(), "CLIENT",
                AuditActionType.LOAN_EARLY_REPAYMENT,
                "Early repayment of loan " + loan.getLoanNumber() + " (payoff=" + payoffAmount
                        + " " + loan.getCurrency().getCode() + ")",
                "LOAN", loan.getId());

        return toLoanResponse(loan);
    }

    // --- Interest rate tables from spec ---

    /**
     * P1-savings-loans-1 (R1-133 / R3-1630): tranza nominalne stope se bira po RSD
     * ekvivalentu iznosa. Ranije se koristio SIROV {@code amount.doubleValue()} bez FX
     * konverzije — 100000 EUR (~11.7M RSD) je padalo u najnizu tranzu (6.25% umesto ~5.00%).
     * Sada se strani iznos konvertuje u RSD pre lookup-a; BigDecimal poredjenja umesto
     * sirovog double-a. Defanzivno: ako FX nije dostupan (npr. exchange servis nedostupan),
     * pada na sirov iznos (RSD krediti — dominantan slucaj — su nepromenjeni).
     */
    private BigDecimal getBaseRate(BigDecimal amount, String currencyCode) {
        BigDecimal rsdAmount = amount;
        if (currencyCode != null && !"RSD".equalsIgnoreCase(currencyCode)) {
            try {
                rsdAmount = currencyConversionService.convert(amount, currencyCode, "RSD");
            } catch (RuntimeException e) {
                log.warn("FX konverzija {} -> RSD nije uspela za nominal-rate lookup ({}), "
                        + "koristim sirov iznos", currencyCode, e.getMessage());
                rsdAmount = amount;
            }
        }
        if (rsdAmount.compareTo(new BigDecimal("500000")) <= 0) return new BigDecimal("6.25");
        if (rsdAmount.compareTo(new BigDecimal("1000000")) <= 0) return new BigDecimal("6.00");
        if (rsdAmount.compareTo(new BigDecimal("2000000")) <= 0) return new BigDecimal("5.75");
        if (rsdAmount.compareTo(new BigDecimal("5000000")) <= 0) return new BigDecimal("5.50");
        if (rsdAmount.compareTo(new BigDecimal("10000000")) <= 0) return new BigDecimal("5.25");
        if (rsdAmount.compareTo(new BigDecimal("20000000")) <= 0) return new BigDecimal("5.00");
        return new BigDecimal("4.75");
    }

    /** R1-657: delegira na {@link LoanType#getMargin()} (jedinstven izvor istine). */
    private BigDecimal getMargin(LoanType type) {
        return type.getMargin();
    }

    // --- Mappers ---

    private LoanRequestResponseDto toRequestResponse(LoanRequest r) {
        return LoanRequestResponseDto.builder()
                .id(r.getId())
                .loanType(r.getLoanType().name())
                .interestType(r.getInterestType().name())
                .amount(r.getAmount())
                .currency(r.getCurrency().getCode())
                .loanPurpose(r.getLoanPurpose())
                .repaymentPeriod(r.getRepaymentPeriod())
                .accountNumber(r.getAccount().getAccountNumber())
                .phoneNumber(r.getPhoneNumber())
                .employmentStatus(r.getEmploymentStatus())
                .monthlyIncome(r.getMonthlyIncome())
                .permanentEmployment(r.getPermanentEmployment())
                .employmentPeriod(r.getEmploymentPeriod())
                .status(r.getStatus().name())
                .createdAt(r.getCreatedAt())
                .clientEmail(r.getClient().getEmail())
                .clientName(r.getClient().getFirstName() + " " + r.getClient().getLastName())
                .build();
    }

    private LoanResponseDto toLoanResponse(Loan l) {
        return LoanResponseDto.builder()
                .id(l.getId())
                .loanNumber(l.getLoanNumber())
                .loanType(l.getLoanType().name())
                .interestType(l.getInterestType().name())
                .amount(l.getAmount())
                .repaymentPeriod(l.getRepaymentPeriod())
                .nominalRate(l.getNominalRate())
                .effectiveRate(l.getEffectiveRate())
                .monthlyPayment(l.getMonthlyPayment())
                .startDate(l.getStartDate())
                .endDate(l.getEndDate())
                .remainingDebt(l.getRemainingDebt())
                .currency(l.getCurrency().getCode())
                .status(l.getStatus().name())
                .accountNumber(l.getAccount().getAccountNumber())
                .loanPurpose(l.getLoanPurpose())
                .createdAt(l.getCreatedAt())
                .build();
    }

    private InstallmentResponseDto toInstallmentResponse(LoanInstallment i) {
        return InstallmentResponseDto.builder()
                .id(i.getId())
                .amount(i.getAmount())
                .principalAmount(i.getPrincipalAmount())
                .interestAmount(i.getInterestAmount())
                .interestRate(i.getInterestRate())
                .currency(i.getCurrency().getCode())
                .expectedDueDate(i.getExpectedDueDate())
                .actualDueDate(i.getActualDueDate())
                .paid(i.getPaid())
                .build();
    }
}
