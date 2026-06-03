package rs.raf.trading.margin.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.DebitFundsRequest;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.margin.dto.CreateCompanyMarginAccountDto;
import rs.raf.trading.margin.dto.CreateMarginAccountDto;
import rs.raf.trading.margin.dto.MarginAccountDto;
import rs.raf.trading.margin.dto.MarginTransactionDto;
import rs.raf.trading.margin.event.MarginAccountBlockedEvent;
import rs.raf.trading.margin.model.CompanyMarginAccount;
import rs.raf.trading.margin.model.MarginAccount;
import rs.raf.trading.margin.model.MarginAccountStatus;
import rs.raf.trading.margin.model.MarginCallPolicy;
import rs.raf.trading.margin.model.MarginTransaction;
import rs.raf.trading.margin.model.MarginTransactionType;
import rs.raf.trading.margin.model.UserMarginAccount;
import rs.raf.trading.margin.repository.MarginAccountRepository;
import rs.raf.trading.margin.repository.MarginTransactionRepository;
import rs.raf.trading.security.TradingUserResolver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Servis za upravljanje margin racunima.
 * <p>
 * Specifikacija: Marzni_Racuni.txt §1-159, Celina 3 - Margin racuni
 * <p>
 * <b>BE-STK-07 (25.05.2026):</b> {@code createForUser} sad prihvata
 * {@code initialMargin}, {@code maintenanceMargin}, {@code bankParticipation}
 * direktno iz DTO-a (zadaje zaposleni). Validacija:
 * {@code 0 < BP < 1}, {@code MM <= IM}, {@code IM > 0}, {@code currency = "RSD"}.
 * <p>
 * {@code deposit}/{@code withdraw} NE preracunavaju MM (BE-STK-07 fix) —
 * MM je fiksiran pri kreiranju i menja se samo eksplicitno.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarginAccountService {

    private final MarginAccountRepository marginAccountRepository;
    private final MarginTransactionRepository marginTransactionRepository;
    private final BankaCoreClient bankaCoreClient;
    private final TradingUserResolver userResolver;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * P2-perf-nplus1-1 (R5 1894): self-referenca (Spring proxy) za poziv
     * {@code @Transactional} {@link #blockEligibleAccounts()} iz ne-transakcionog
     * {@code @Scheduled} {@link #checkMaintenanceMargin()}. Direktan
     * {@code this.blockEligibleAccounts()} bi bio self-invocation i AOP
     * {@code @Transactional} bi bio NO-OP (ista zamka kao stari OptionScheduler).
     * Field-injekcija (NE konstruktor) + {@code @Lazy} izbegava
     * konstrukciono-ciklusnu zavisnost i NE menja {@code @RequiredArgsConstructor}
     * potpis (unit testovi konstruisu servis sa 5 arg-a; tamo je {@code self}
     * null pa {@link #self()} fallback-uje na {@code this} — dovoljno za direktne
     * unit pozive gde Tx ionako nije aktivan).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private MarginAccountService self;

    private MarginAccountService self() {
        return self != null ? self : this;
    }

    /**
     * <b>LEGACY (R1 768):</b> podrazumevani procenat ucestva banke (50%) — koristi
     * se SAMO u legacy putanji {@link #createForUser} kad DTO ne specifikuje
     * {@code bankParticipation} eksplicitno. Intended flow je BE-STK-07
     * employee-driven (eksplicitni IM/MM/BP iz DTO-a); legacy grana je zadrzana
     * radi backwards-compat sa starijim klijentima i odgovarajucim test fixtures.
     * Kad se legacy grana aktivira, loguje se {@code WARN} (vidi {@link #createForUser}).
     */
    private static final BigDecimal DEFAULT_BANK_PARTICIPATION = new BigDecimal("0.50");

    /**
     * <b>LEGACY (R1 768):</b> faktor za izracunavanje MM (50% od IM) — koristi se
     * SAMO kad DTO ne specifikuje MM eksplicitno (backwards-compat, vidi
     * {@link #DEFAULT_BANK_PARTICIPATION}).
     */
    private static final BigDecimal LEGACY_MAINTENANCE_FACTOR = new BigDecimal("0.50");

    /**
     * Kreira novi margin racun za autentifikovanog korisnika (klijenta).
     *
     * <p>BE-STK-07: Validacija IM/MM/BP iz DTO-a. Ako su zadati, koristi se
     * eksplicitne vrednosti. Ako nisu (legacy putanja), koristi se
     * {@code initialDeposit / (1 - BP)} formula.
     */
    @Transactional
    public MarginAccountDto createForUser(CreateMarginAccountDto dto) {
        Long userId = currentUserId();
        if (dto == null || dto.getAccountId() == null) {
            throw new IllegalArgumentException("Account id and initial deposit are required.");
        }

        // BE-STK-07: validacija depozita PRE banka-core fetch-a za legacy putanju
        // (test fixture sa null IM/MM/BP ocekuje "Initial deposit must be greater than zero"
        // bez stub-ovanja getAccount).
        boolean hasExplicitParams = dto.getInitialMargin() != null
                && dto.getMaintenanceMargin() != null
                && dto.getBankParticipation() != null;
        if (!hasExplicitParams) {
            if (dto.getInitialDeposit() == null
                    || dto.getInitialDeposit().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Initial deposit must be greater than zero.");
            }
        }

        // Bazni racun se cita preko banka-core (monolit: accountRepository.findForUpdateById).
        InternalAccountDto account;
        try {
            account = bankaCoreClient.getAccount(dto.getAccountId());
        } catch (BankaCoreClientException ex) {
            throw new IllegalArgumentException("Account not found.");
        }

        if (account.ownerClientId() == null || !userId.equals(account.ownerClientId())) {
            throw new IllegalStateException("You are not allowed to create a margin account for this base account.");
        }
        if (!"ACTIVE".equalsIgnoreCase(account.status())) {
            throw new IllegalArgumentException("Base account must be active.");
        }
        // BE-STK-04: currency uvek RSD po Marzni_Racuni.txt §17.
        if (!"RSD".equalsIgnoreCase(account.currencyCode())) {
            throw new IllegalArgumentException("Margin accounts must use RSD currency (got: "
                    + account.currencyCode() + ").");
        }
        if (!marginAccountRepository.findByAccountId(account.id()).isEmpty()) {
            throw new IllegalArgumentException("Margin account already exists for this base account.");
        }

        BigDecimal initialMargin;
        BigDecimal maintenanceMargin;
        BigDecimal bankParticipation;
        BigDecimal loanValue;
        BigDecimal initialDeposit;

        if (dto.getInitialMargin() != null && dto.getMaintenanceMargin() != null
                && dto.getBankParticipation() != null) {
            // BE-STK-07: eksplicitne vrednosti od strane zaposlenog.
            initialMargin = dto.getInitialMargin().setScale(4, RoundingMode.HALF_UP);
            maintenanceMargin = dto.getMaintenanceMargin().setScale(4, RoundingMode.HALF_UP);
            bankParticipation = dto.getBankParticipation().setScale(4, RoundingMode.HALF_UP);

            // R1 770: deljena validacija (IM>0, MM>=0, MM<=IM, 0<BP<1) — ista kao
            // company-create grana (vidi createForCompany / validateMarginParams).
            validateMarginParams(initialMargin, maintenanceMargin, bankParticipation);

            // Loan value na pocetku = nula (Marzni_Racuni.txt §9).
            loanValue = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            // Zaposleni je naveo IM eksplicitno; pocetni depozit = IM (sve sa user-ovog racuna).
            initialDeposit = initialMargin;
        } else {
            // R1 768: LEGACY putanja — izracunaj IM preko formule iz initialDeposit i
            // DEFAULT BP. Intended flow je BE-STK-07 (eksplicitni IM/MM/BP); legacy
            // grana se zadrzava radi backwards-compat. Logujemo da je aktivirana.
            log.warn("Margin account creation za account {} koristi LEGACY formula putanju "
                    + "(DTO bez eksplicitnih IM/MM/BP) — preferira se BE-STK-07 employee-driven flow.",
                    dto.getAccountId());
            if (dto.getInitialDeposit() == null
                    || dto.getInitialDeposit().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Initial deposit must be greater than zero.");
            }
            initialDeposit = dto.getInitialDeposit();
            bankParticipation = DEFAULT_BANK_PARTICIPATION;
            BigDecimal divisor = BigDecimal.ONE.subtract(bankParticipation);
            initialMargin = initialDeposit.divide(divisor, 4, RoundingMode.HALF_UP);
            loanValue = initialMargin.subtract(initialDeposit).setScale(4, RoundingMode.HALF_UP);
            maintenanceMargin = initialMargin.multiply(LEGACY_MAINTENANCE_FACTOR)
                    .setScale(4, RoundingMode.HALF_UP);
        }

        // Pre-check: stvarnu garanciju daje debitFunds (banka-core 409).
        BigDecimal availableBalance = account.availableBalance() == null
                ? BigDecimal.ZERO
                : account.availableBalance();
        if (availableBalance.compareTo(initialDeposit) < 0) {
            throw new IllegalArgumentException("Insufficient available balance for initial margin deposit.");
        }

        // Debit baznog racuna za pocetni margin depozit.
        try {
            bankaCoreClient.debitFunds(
                    "margin-create-" + dto.getAccountId(),
                    new DebitFundsRequest(dto.getAccountId(), initialDeposit, BigDecimal.ZERO,
                            account.currencyCode(), "Initial margin deposit"));
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() == 409) {
                throw new IllegalArgumentException("Insufficient available balance for initial margin deposit.");
            }
            throw ex;
        }

        // P2-money-tx-1 (R3 1577): debitFunds je out-of-process (banka-core) i
        // izvrsava se UNUTAR ove @Transactional metode. Spring rollback NE moze da
        // vrati taj debit ako lokalni save (MarginAccount/MarginTransaction) baci
        // posle uspesnog debita — npr. DataIntegrityViolation na §57 uniqueness race,
        // optimistic-lock, ili constraint. Bez kompenzacije = klijent debitovan ali
        // margin racun ne postoji → IZGUBLJEN DEPOZIT. Kompenzujemo eksplicitnim
        // creditFunds-om (jednostrani kredit nazad na bazni racun) i re-throw-ujemo
        // originalnu gresku, tako da pozivalac vidi neuspeh a novac nije nestao.
        MarginAccount savedMarginAccount;
        try {
            UserMarginAccount marginAccount = UserMarginAccount.builder()
                    .accountId(account.id())
                    .accountNumber(account.accountNumber())
                    .userId(userId)
                    .currency("RSD")
                    .initialMargin(initialMargin)
                    .loanValue(loanValue)
                    .maintenanceMargin(maintenanceMargin)
                    .bankParticipation(bankParticipation)
                    .reservedMargin(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                    .status(MarginAccountStatus.ACTIVE)
                    .build();
            savedMarginAccount = marginAccountRepository.save(marginAccount);

            marginTransactionRepository.save(
                    MarginTransaction.builder()
                            .marginAccount(savedMarginAccount)
                            .type(MarginTransactionType.DEPOSIT)
                            .amount(initialDeposit.setScale(4, RoundingMode.HALF_UP))
                            .description("Initial margin deposit")
                            .build()
            );
        } catch (RuntimeException localSaveFailure) {
            compensateInitialDebit(dto.getAccountId(), initialDeposit,
                    account.currencyCode(), "margin-create-" + dto.getAccountId(),
                    "Compensation: reverse initial margin deposit");
            throw localSaveFailure;
        }

        log.info("Created margin account {} for user {} on base account {} (IM={}, MM={}, BP={})",
                savedMarginAccount.getId(), userId, account.id(),
                initialMargin, maintenanceMargin, bankParticipation);

        return toDto(savedMarginAccount);
    }

    /**
     * Vraca sve margin racune za autentifikovanog korisnika.
     */
    public List<MarginAccountDto> getMyMarginAccounts() {
        Long clientId = currentUserId();

        List<MarginAccountDto> accounts = marginAccountRepository.findByUserId(clientId)
                .stream()
                .map(this::toDto)
                .toList();

        log.info("Fetched {} margin accounts for client {}", accounts.size(), clientId);
        return accounts;
    }

    /**
     * BE-STK-06: kreira marzni racun za kompaniju (COMPANY vlasnistvo).
     *
     * <p>Marzni_Racuni.txt §25-27 + §43-55: zaposleni (supervizor/admin) zadaje
     * eksplicitne {@code initialMargin}/{@code maintenanceMargin}/
     * {@code bankParticipation} za kompaniju. Mirror eksplicitne grane
     * {@link #createForUser}: ista validacija (IM&gt;0, MM&le;IM, 0&lt;BP&lt;1,
     * currency RSD), {@code loanValue=0}, status ACTIVE, bank-debit baznog racuna
     * iznosom IM. {@code employeeId} se NE cita iz tela — identitet je iz JWT-a.
     *
     * <p>Marzni_Racuni.txt §57: kompanija moze imati samo jedan marzni racun.
     */
    @Transactional
    public MarginAccountDto createForCompany(CreateCompanyMarginAccountDto dto) {
        // Identitet: samo zaposleni (supervizor/admin) sme da kreira racun kompanije.
        requireEmployee();

        if (dto == null || dto.getAccountId() == null || dto.getCompanyId() == null) {
            throw new IllegalArgumentException("Account id and company id are required.");
        }

        // Marzni_Racuni.txt §57: jedan marzni racun po kompaniji.
        if (marginAccountRepository.findByCompanyId(dto.getCompanyId()).isPresent()) {
            throw new IllegalArgumentException("Margin account already exists for this company.");
        }

        // Bazni racun kompanije (banka-core) sa kog se skida pocetni margin depozit.
        InternalAccountDto account;
        try {
            account = bankaCoreClient.getAccount(dto.getAccountId());
        } catch (BankaCoreClientException ex) {
            throw new IllegalArgumentException("Account not found.");
        }

        if (!"ACTIVE".equalsIgnoreCase(account.status())) {
            throw new IllegalArgumentException("Base account must be active.");
        }
        // BE-STK-04: currency uvek RSD po Marzni_Racuni.txt §17.
        if (!"RSD".equalsIgnoreCase(account.currencyCode())) {
            throw new IllegalArgumentException("Margin accounts must use RSD currency (got: "
                    + account.currencyCode() + ").");
        }
        if (!marginAccountRepository.findByAccountId(account.id()).isEmpty()) {
            throw new IllegalArgumentException("Margin account already exists for this base account.");
        }

        // Validacija + scale eksplicitnih IM/MM/BP — identicno explicit-params grani createForUser.
        BigDecimal initialMargin = dto.getInitialMargin().setScale(4, RoundingMode.HALF_UP);
        BigDecimal maintenanceMargin = dto.getMaintenanceMargin().setScale(4, RoundingMode.HALF_UP);
        BigDecimal bankParticipation = dto.getBankParticipation().setScale(4, RoundingMode.HALF_UP);
        validateMarginParams(initialMargin, maintenanceMargin, bankParticipation);

        // Loan value na pocetku = nula (Marzni_Racuni.txt §9). Pocetni depozit = IM.
        BigDecimal loanValue = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        BigDecimal initialDeposit = initialMargin;

        BigDecimal availableBalance = account.availableBalance() == null
                ? BigDecimal.ZERO
                : account.availableBalance();
        if (availableBalance.compareTo(initialDeposit) < 0) {
            throw new IllegalArgumentException("Insufficient available balance for initial margin deposit.");
        }

        try {
            bankaCoreClient.debitFunds(
                    "margin-create-company-" + dto.getAccountId(),
                    new DebitFundsRequest(dto.getAccountId(), initialDeposit, BigDecimal.ZERO,
                            account.currencyCode(), "Initial company margin deposit"));
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() == 409) {
                throw new IllegalArgumentException("Insufficient available balance for initial margin deposit.");
            }
            throw ex;
        }

        // P2-money-tx-1 (R3 1577): isti kompenzacioni obrazac kao createForUser —
        // debitFunds je vec izvrsen out-of-process; lokalni save-fail bez kompenzacije
        // ostavlja kompaniju debitovanu bez margin racuna (izgubljen depozit).
        MarginAccount savedMarginAccount;
        try {
            CompanyMarginAccount marginAccount = CompanyMarginAccount.builder()
                    .accountId(account.id())
                    .accountNumber(account.accountNumber())
                    .companyId(dto.getCompanyId())
                    .currency("RSD")
                    .initialMargin(initialMargin)
                    .loanValue(loanValue)
                    .maintenanceMargin(maintenanceMargin)
                    .bankParticipation(bankParticipation)
                    .reservedMargin(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                    .status(MarginAccountStatus.ACTIVE)
                    .build();
            savedMarginAccount = marginAccountRepository.save(marginAccount);

            marginTransactionRepository.save(
                    MarginTransaction.builder()
                            .marginAccount(savedMarginAccount)
                            .type(MarginTransactionType.DEPOSIT)
                            .amount(initialDeposit.setScale(4, RoundingMode.HALF_UP))
                            .description("Initial company margin deposit")
                            .build()
            );
        } catch (RuntimeException localSaveFailure) {
            compensateInitialDebit(dto.getAccountId(), initialDeposit,
                    account.currencyCode(), "margin-create-company-" + dto.getAccountId(),
                    "Compensation: reverse initial company margin deposit");
            throw localSaveFailure;
        }

        log.info("Created company margin account {} for company {} on base account {} (IM={}, MM={}, BP={})",
                savedMarginAccount.getId(), dto.getCompanyId(), account.id(),
                initialMargin, maintenanceMargin, bankParticipation);

        return toDto(savedMarginAccount);
    }

    /**
     * BE-STK-06: vraca marzni racun kompanije po {@code companyId}
     * (Marzni_Racuni.txt §61). Samo zaposleni sme da ga cita.
     *
     * @throws EntityNotFoundException ako kompanija nema marzni racun
     */
    public MarginAccountDto getCompanyMarginAccount(Long companyId) {
        requireEmployee();

        CompanyMarginAccount account = marginAccountRepository.findByCompanyId(companyId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Margin account for company with id: " + companyId + " not found."));

        return toDto(account);
    }

    /**
     * Uplata sredstava na margin racun.
     * <p>BE-STK-07: NE preracunava MM (maintenance margin je fiksiran pri
     * kreiranju). Marzni_Racuni.txt §139: "kada InitialMargin predje MaintenanceMargin,
     * racun se odblokira" — odblokiranje se aktivira ako uplata gurne IM iznad MM.
     */
    @Transactional
    public void deposit(Long marginAccountId, BigDecimal amount) {
        // R1 767: `compareTo(ZERO) < 1` je nejasna idioma za `<= 0`.
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Amount must be a positive number.");

        Long clientId = currentUserId();

        // P2-concurrency-locks-1 (R1-465/R3-1603): pessimistic lock (findByIdForUpdate)
        // paritet sa withdraw (P1-8). Bez lock-a, paralelni deposit/withdraw/fill nad
        // istim margin racunom citaju isti initialMargin i lost-update istisnu jedan
        // pomeraj; @Version bi bacio 500 (OptimisticLock) umesto da serijalizuje.
        // findByIdForUpdate serijalizuje konkurentne pisce → cista naplata bez 500.
        MarginAccount account = marginAccountRepository.findByIdForUpdate(marginAccountId)
                .orElseThrow(
                        () -> new EntityNotFoundException("Account with id: " + marginAccountId + " not found.")
                );

        if (!clientId.equals(account.getUserId()))
            throw new IllegalStateException("Only the owner of margin account with id = " + marginAccountId + " can deposit funds.");

        // BE-STK-07: increment IM, ne dodirivati MM (MM je fiksiran pri kreiranju).
        account.setInitialMargin(account.getInitialMargin().add(amount));

        // Marzni_Racuni.txt §139: ako raspoloziva marza predje MM, racun se odblokira.
        // R1 769: deljena margin-call politika (prag = RASPOLOZIVA marza, ne sirov IM) —
        // konzistentno sa blok scheduler-om i post-fill margin call-om; racun sa
        // zarobljenom rezervacijom (hold za in-flight BUY) ostaje blokiran dok
        // availableInitialMargin ne predje MM.
        boolean isBlocked = account.getStatus().equals(MarginAccountStatus.BLOCKED);
        if (isBlocked && MarginCallPolicy.shouldUnblock(account)) {
            account.setStatus(MarginAccountStatus.ACTIVE);
        }

        marginAccountRepository.save(account);

        String transactionDescription =
                "Executed transaction. Amount deposited: " + amount + ". Current balance: " + account.getInitialMargin() + ".";

        MarginTransaction transaction = MarginTransaction.builder()
                .marginAccount(account)
                .type(MarginTransactionType.DEPOSIT)
                .amount(amount)
                .description(transactionDescription)
                .build();

        marginTransactionRepository.save(transaction);

        log.info("Deposit {} to margin account {}", amount, marginAccountId);
    }

    /**
     * Isplata sredstava sa margin racuna.
     * <p>BE-STK-07: NE preracunava MM (fixed pri kreiranju).
     * Marzni_Racuni.txt §159: "pare sa marznog racuna ne mogu skinuti ako je racun blokiran,
     * ili ako skidanjem para idemo ispod MaintenanceMargin vrednosti".
     */
    @Transactional
    public void withdraw(Long marginAccountId, BigDecimal amount) {
        // R1 767: `compareTo(ZERO) < 1` je nejasna idioma za `<= 0`.
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Amount must be a positive number.");

        Long clientId = currentUserId();

        // P1-8: pessimistic lock (findByIdForUpdate) — guard ne sme da trci sa
        // concurrent MarginOrderSettlementService.reserveForMarginBuy koji menja
        // reservedMargin u istom redu. Mirror obrazac iz settlement servisa.
        MarginAccount account = marginAccountRepository.findByIdForUpdate(marginAccountId).orElseThrow(
                () -> new EntityNotFoundException("Account with id: " + marginAccountId + " not found.")
        );

        if (!clientId.equals(account.getUserId()))
            throw new IllegalStateException("Only the owner of margin account with id = " + marginAccountId + " can withdraw funds.");

        if (!account.getStatus().equals(MarginAccountStatus.ACTIVE))
            throw new IllegalStateException("Account with id: " + marginAccountId + " is not active.");

        // P1-8: guard mora da koristi RASPOLOZIVU IM (IM - reservedMargin), ne sirovu IM.
        // reservedMargin je hold izrezan iz IM za in-flight margin BUY; ako se ignorise,
        // klijent moze da povuce sredstva rezervisana za nepodmiren BUY i ostavi
        // availableInitialMargin ispod MaintenanceMargin (banka izlozena).
        boolean withdrawalBelowMaintenance =
                account.getAvailableInitialMargin().subtract(amount).compareTo(account.getMaintenanceMargin()) < 0;

        if (withdrawalBelowMaintenance)
            throw new IllegalArgumentException(
                    "Funds in the account cannot be below " + account.getMaintenanceMargin() + " amount."
            );

        // BE-STK-07: decrement IM, ne dodirivati MM.
        account.setInitialMargin(account.getInitialMargin().subtract(amount));

        marginAccountRepository.save(account);

        String description = "Executed transaction. Amount withdrawn: " + amount + ". Current balance: " + account.getInitialMargin() + ".";

        MarginTransaction transaction = MarginTransaction.builder()
                .marginAccount(account)
                .type(MarginTransactionType.WITHDRAWAL)
                .amount(amount)
                .description(description)
                .build();

        marginTransactionRepository.save(transaction);

        log.info("Withdraw {} from margin account {}", amount, marginAccountId);
    }

    /**
     * Dnevna provera maintenance margine za sve aktivne margin racune.
     *
     * <p><b>BE-STK-04 (2-step block, H2 test compat):</b> umesto atomic UPDATE
     * sa RETURNING klauzulom (PG-only), sad koristi 2-step JPA pattern:
     * (1) {@link MarginAccountRepository#findEligibleForBlock} → lista id-eva
     * koji ispunjavaju MM>IM AND status=ACTIVE; (2)
     * {@link MarginAccountRepository#bulkUpdateStatus} flipa ih ACTIVE→BLOCKED
     * unutar iste Tx. Race window izmedju SELECT i UPDATE pokriven Tx context-om
     * (cela {@code checkMaintenanceMargin} je @Transactional; depozit/withdraw
     * idu sa pessimistic lock nad ACTIVE redovima — ako u medjuvremenu redovi
     * postanu BLOCKED, depozit puca pre commit-a).
     */
    /**
     * Scheduler entry-point (NE {@code @Transactional}). P2-perf-nplus1-1 (R5 1894):
     * lock-during-IO fix. Ranija verzija je radila CELU operaciju u jednoj
     * transakciji — ukljucujuci per-account HTTP {@code getUserById} (email lookup)
     * i {@code publishEvent} — pa su DB lock-ovi nad blokiranim redovima stajali
     * dok je mrezni I/O ka banka-core trajao (N+1 preko mreze, zaobilazi i kes).
     * Sad: (1) {@link #blockEligibleAccounts()} radi SAMO DB blok u kratkoj Tx i
     * vraca snapshot-e; (2) email lookup + event publish se radi POSLE commit-a,
     * van transakcije (kao dividend-cron). Lock-ovi se otpuste pre I/O.
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void checkMaintenanceMargin() {
        log.info("Running daily maintenance margin check...");

        // Poziv KROZ proxy (self) da @Transactional zaista vazi.
        List<BlockedAccountSnapshot> blocked = self().blockEligibleAccounts();

        if (blocked.isEmpty()) {
            log.info("Daily maintenance margin check completed. No accounts blocked.");
            return;
        }

        // POSLE commit-a (van Tx): N+1 email lookup + event publish — DB lock-ovi
        // su vec otpusteni, mrezni I/O ne drzi blokirane redove zakljucane.
        publishMarginCallNotifications(blocked);

        log.info("Daily maintenance margin check completed. Amount of blocked accounts : {}.", blocked.size());
    }

    /**
     * P2-perf-nplus1-1 (R5 1894): kratka Tx — bulk-block ACTIVE→BLOCKED i izvuci
     * snapshot-e za notifikaciju. Bez mreznog I/O u Tx-u. {@code Propagation.REQUIRES_NEW}
     * nije potreban (scheduler poziva direktno, nema spoljnu Tx), ali metoda mora
     * biti pozvana kroz Spring proxy — zato je {@code @Scheduled} entry razdvojen.
     */
    @Transactional
    public List<BlockedAccountSnapshot> blockEligibleAccounts() {
        // Step 1: pronalazi sve id-eve eligible za blokiranje (ACTIVE + MM>IM).
        List<Long> blockedIds = marginAccountRepository.findEligibleForBlock(
                MarginAccountStatus.ACTIVE
        );

        if (blockedIds.isEmpty()) {
            return List.of();
        }

        // Step 2: bulk UPDATE statusa ACTIVE → BLOCKED unutar iste Tx.
        marginAccountRepository.bulkUpdateStatus(blockedIds, MarginAccountStatus.BLOCKED);

        // Posle blokade: resolve detalje (userId/MM/IM) za notification publish.
        // Bezbedno: redovi su sada BLOCKED, vise ne mogu da se menjaju kroz
        // standardne deposit/withdraw flow-ove (koji rade samo nad ACTIVE).
        List<MarginAccount> blockedAccounts = marginAccountRepository.findAllById(blockedIds);

        List<BlockedAccountSnapshot> snapshots = new java.util.ArrayList<>(blockedAccounts.size());
        for (MarginAccount account : blockedAccounts) {
            BigDecimal maintenanceMargin = account.getMaintenanceMargin();
            BigDecimal initialMargin = account.getInitialMargin();
            // P1-margin-1 (R3 1548): deficit se racuna nad RASPOLOZIVOM marzom
            // (IM − reservedMargin), konzistentno sa eligibility uslovom
            // (findEligibleForBlock) — racun je blokiran jer je raspoloziva marza
            // (ne sirov IM) pala ispod MM.
            BigDecimal availableImValue = maintenanceMargin != null && initialMargin != null
                    ? account.getAvailableInitialMargin()
                    : null;
            BigDecimal deficit = availableImValue != null
                    ? maintenanceMargin.subtract(availableImValue)
                    : BigDecimal.ZERO;
            snapshots.add(new BlockedAccountSnapshot(
                    account.getId(), account.getUserId(), maintenanceMargin, initialMargin, deficit));
        }
        return snapshots;
    }

    /**
     * P2-perf-nplus1-1 (R5 1894): POSLE-commit korak — email lookup (HTTP) +
     * event publish, van transakcije. Email-ovi se razresavaju batch-om (jedan
     * lookup po distinct userId), pa se ne ponavlja za vise racuna istog vlasnika.
     */
    void publishMarginCallNotifications(List<BlockedAccountSnapshot> blocked) {
        // Batch email resolve: jedan getUserById po distinct (non-null) userId.
        java.util.Map<Long, String> emailByUserId = new java.util.HashMap<>();
        blocked.stream()
                .map(BlockedAccountSnapshot::userId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(uid -> emailByUserId.put(uid, resolveOwnerEmail(uid)));

        for (BlockedAccountSnapshot snap : blocked) {
            String ownerEmail = snap.userId() != null ? emailByUserId.get(snap.userId()) : null;
            eventPublisher.publishEvent(
                    new MarginAccountBlockedEvent(
                            // R1 381: userId + marginAccountId za in-app (bell) notifikaciju.
                            // Company margin racun ima userId==null → listener preskace in-app.
                            snap.userId(),
                            snap.accountId(),
                            ownerEmail,
                            String.valueOf(snap.maintenanceMargin()),
                            String.valueOf(snap.initialMargin()),
                            snap.deficit().toString()
                    )
            );
            log.warn(
                    "MARGIN CALL: Account {} blocked. initialMargin={}, maintenanceMargin={}",
                    snap.accountId(),
                    snap.initialMargin(),
                    snap.maintenanceMargin()
            );
        }
    }

    /**
     * P2-perf-nplus1-1 (R5 1894): lagani snapshot blokiranog margin racuna —
     * prenosi se iz Tx-a (gde su entiteti ucitani) u post-commit notifikacioni
     * korak, da se entiteti ne diraju van Tx-a (lazy/detached zamke).
     */
    record BlockedAccountSnapshot(
            Long accountId,
            Long userId,
            BigDecimal maintenanceMargin,
            BigDecimal initialMargin,
            BigDecimal deficit) {
    }

    public List<MarginTransactionDto> getTransactions(Long marginAccountId) {
        Long clientId = currentUserId();

        MarginAccount marginAccount = marginAccountRepository.findById(marginAccountId).orElseThrow(
                () -> new EntityNotFoundException("Margin account with id: " + marginAccountId + " does not exist.")
        );

        // P1-margin-1 (R1 203): null-safe ownership check. CompanyMarginAccount ima
        // userId==null → {@code marginAccount.getUserId().equals(...)} bi bacio NPE
        // (→ 500). Preokrenuto na {@code clientId.equals(marginAccount.getUserId())}:
        // company racun (userId==null) ne pripada klijentu → 403, ne 500.
        if (!clientId.equals(marginAccount.getUserId()))
            throw new IllegalStateException("Only the owner of the margin account with id = " + marginAccountId + " can access margin account transactions.");

        return marginTransactionRepository.findByMarginAccountIdOrderByCreatedAtDesc(marginAccountId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    // ── Helper metode ───────────────────────────────────────────────────────────

    private Long currentUserId() {
        UserContext me = userResolver.resolveCurrent();
        if (!UserRole.CLIENT.equals(me.userRole())) {
            throw new IllegalStateException("Only clients can manage margin accounts.");
        }
        return me.userId();
    }

    /**
     * BE-STK-06 / P2-authz-method-1 (R1 468/467): garantuje da je trenutni akter
     * SUPERVIZOR ili ADMIN. Marzni racune kompanija po Marzni_Racuni.txt §25-27
     * zadaje supervizor/admin — NE bilo koji zaposleni.
     *
     * <p><b>Bug koji se zatvara:</b> {@code UserContext.userRole} je u
     * trading-service identitetu samo "CLIENT" ili "EMPLOYEE" (JWT role iz
     * banka-core) — NE razlikuje AGENT od SUPERVISOR/ADMIN. Ranija provera
     * {@code UserRole.EMPLOYEE.equals(role)} je propustala SVAKOG zaposlenog,
     * ukljucujuci AGENTA, da kreira/cita marzni racun kompanije (i HTTP matcher
     * {@code POST/GET /margin-accounts/company → hasAnyRole(ADMIN,EMPLOYEE)} je
     * isto puštao agenta jer agent nosi ROLE_EMPLOYEE). Fino-granularni
     * SUPERVISOR/ADMIN authority dolazi iz JWT permisija
     * ({@code TradingJwtAuthenticationFilter} ih razresava preko banka-core).
     * Mirror {@code OtcService.ensureOtcAccess} employee-grane.
     *
     * @return numericki id zaposlenog
     */
    private Long requireEmployee() {
        UserContext me = userResolver.resolveCurrent();
        if (!UserRole.EMPLOYEE.equals(me.userRole())) {
            throw new IllegalStateException("Only employees can manage company margin accounts.");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isSupervisorOrAdmin = auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ADMIN".equals(a)
                        || "SUPERVISOR".equals(a)
                        || "ROLE_ADMIN".equals(a)
                        || "ROLE_SUPERVISOR".equals(a));
        if (!isSupervisorOrAdmin) {
            throw new AccessDeniedException(
                    "Only supervisors and admins can manage company margin accounts.");
        }
        return me.userId();
    }

    /**
     * BE-STK-06: zajednicka validacija eksplicitnih IM/MM/BP vrednosti
     * (zadaje zaposleni). Iste poruke kao explicit-params grana
     * {@link #createForUser}: {@code IM>0}, {@code MM>=0}, {@code MM<=IM},
     * {@code 0<BP<1}.
     */
    private void validateMarginParams(BigDecimal initialMargin, BigDecimal maintenanceMargin,
                                      BigDecimal bankParticipation) {
        if (initialMargin.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("InitialMargin must be greater than zero.");
        }
        if (maintenanceMargin.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("MaintenanceMargin must be non-negative.");
        }
        if (maintenanceMargin.compareTo(initialMargin) > 0) {
            throw new IllegalArgumentException("MaintenanceMargin must not exceed InitialMargin.");
        }
        if (bankParticipation.compareTo(BigDecimal.ZERO) <= 0
                || bankParticipation.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("BankParticipation must be strictly between 0 and 1.");
        }
    }

    /**
     * P2-money-tx-1 (R3 1577): kompenzuje pocetni margin debit kad lokalni save
     * padne posle uspesnog out-of-process {@code debitFunds}. Vraca {@code amount}
     * na bazni racun jednostranim {@code creditFunds}-om.
     *
     * <p>Idempotency kljuc kompenzacije je IZVEDEN iz debit kljuca ({@code <debitKey>-compensate})
     * — mora biti RAZLICIT od debit kljuca (inace bi banka-core dedup vratio cached
     * debit rezultat umesto da izvrsi credit), ali deterministican da retry iste
     * neuspele kreacije ne kreditira dvaput.
     *
     * <p>Kompenzaciona greska se LOGUJE ali NE prikriva originalnu — pozivalac
     * mora videti pravi uzrok pada; rezidual (debit ostao, credit pao) ide u
     * out-of-band reconciliation (kao i ostali interbank/SAGA out-of-process legovi).
     */
    private void compensateInitialDebit(Long accountId, BigDecimal amount,
                                        String currencyCode, String debitIdempotencyKey,
                                        String description) {
        try {
            bankaCoreClient.creditFunds(
                    debitIdempotencyKey + "-compensate",
                    new CreditFundsRequest(accountId, amount, BigDecimal.ZERO,
                            currencyCode, description));
            log.warn("Compensated initial margin debit on account {} ({} {}) after local save failure.",
                    accountId, amount, currencyCode);
        } catch (RuntimeException compensationFailure) {
            log.error("CRITICAL: failed to compensate initial margin debit on account {} ({} {}) — "
                            + "funds debited but margin account not created; manual reconciliation required: {}",
                    accountId, amount, currencyCode, compensationFailure.getMessage());
        }
    }

    private String resolveOwnerEmail(Long clientId) {
        if (clientId == null) {
            return null;
        }
        try {
            InternalUserDto user = bankaCoreClient.getUserById(UserRole.CLIENT, clientId);
            return user.email();
        } catch (RuntimeException ex) {
            log.warn("Margin call: nije moguce razresiti email vlasnika klijenta {}: {}",
                    clientId, ex.getMessage());
            return null;
        }
    }

    private MarginAccountDto toDto(MarginAccount marginAccount) {
        Long companyId = marginAccount instanceof CompanyMarginAccount company
                ? company.getCompanyId()
                : null;
        return MarginAccountDto.builder()
                .id(marginAccount.getId())
                .accountId(marginAccount.getAccountId())
                .accountNumber(marginAccount.getAccountNumber())
                .userId(marginAccount.getUserId())
                .companyId(companyId)
                .initialMargin(marginAccount.getInitialMargin())
                .loanValue(marginAccount.getLoanValue())
                .maintenanceMargin(marginAccount.getMaintenanceMargin())
                .bankParticipation(marginAccount.getBankParticipation())
                .status(marginAccount.getStatus() != null ? marginAccount.getStatus().name() : null)
                .createdAt(marginAccount.getCreatedAt())
                .build();
    }

    private MarginTransactionDto toDto(MarginTransaction transaction) {
        return MarginTransactionDto.builder()
                .id(transaction.getId())
                .marginAccountId(transaction.getMarginAccount() != null ? transaction.getMarginAccount().getId() : null)
                .type(transaction.getType() != null ? transaction.getType().name() : null)
                .amount(transaction.getAmount())
                .description(transaction.getDescription())
                .createdAt(transaction.getCreatedAt())
                .build();
    }
}
