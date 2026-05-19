package rs.raf.banka2_bek.internalapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.CommitFundsRequest;
import rs.raf.banka2.contracts.internal.CommitFundsResponse;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.CreditFundsResponse;
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
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.internalapi.model.FundReservation;
import rs.raf.banka2_bek.internalapi.model.FundReservationStatus;
import rs.raf.banka2_bek.internalapi.repository.FundReservationRepository;
import rs.raf.banka2_bek.transaction.model.Transaction;
import rs.raf.banka2_bek.transaction.repository.TransactionRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Jezgro SAGA seam-a za interni /internal/funds/** API.
 * Integrise se sa AccountRepository (pesimisticki lock),
 * FundReservationRepository i TransactionRepository (direktno,
 * jer TransactionService API je previse vezan za Payment/Transfer domen).
 */
@Service
public class InternalFundsService {

    private static final Logger log = LoggerFactory.getLogger(InternalFundsService.class);

    private final AccountRepository accountRepository;
    private final FundReservationRepository fundReservationRepository;
    private final TransactionRepository transactionRepository;
    private final InternalIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    /**
     * Maticni broj drzave (Republike Srbije) — drzava je u sistemu Firma sa RSD
     * tekucim racunom. Isti property koji koristi {@code TaxService} za naplatu
     * poreza ({@code state.registration-number}); replicira se ovde da bi interni
     * tax-collect endpoint razresavao drzavni racun na identican nacin.
     */
    @Value("${state.registration-number}")
    private String stateRegistrationNumber;

    public InternalFundsService(AccountRepository accountRepository,
                                FundReservationRepository fundReservationRepository,
                                TransactionRepository transactionRepository,
                                InternalIdempotencyService idempotencyService,
                                ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.fundReservationRepository = fundReservationRepository;
        this.transactionRepository = transactionRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    // ─── Idempotent facade metode (findCached + operacija + store atomicno) ────

    /**
     * Idempotent wrapper: reserve + idempotency store u jednoj transakciji.
     */
    @Transactional
    public ReserveFundsResponse reserveIdempotent(String idempotencyKey, ReserveFundsRequest req) {
        Optional<rs.raf.banka2_bek.internalapi.model.InternalRequest> cached =
                idempotencyService.findCached(idempotencyKey);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getResponseBody(), ReserveFundsResponse.class);
            } catch (Exception e) {
                log.warn("Idempotency deserialization failed for key {}: {}", idempotencyKey, e.getMessage());
            }
        }
        ReserveFundsResponse result = reserve(req);
        storeIdempotency(idempotencyKey, "/internal/funds/reserve", result);
        return result;
    }

    /**
     * Idempotent wrapper: commit + idempotency store u jednoj transakciji.
     */
    @Transactional
    public CommitFundsResponse commitIdempotent(String idempotencyKey, String reservationId,
                                                CommitFundsRequest req) {
        Optional<rs.raf.banka2_bek.internalapi.model.InternalRequest> cached =
                idempotencyService.findCached(idempotencyKey);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getResponseBody(), CommitFundsResponse.class);
            } catch (Exception e) {
                log.warn("Idempotency deserialization failed for key {}: {}", idempotencyKey, e.getMessage());
            }
        }
        CommitFundsResponse result = commit(reservationId, req);
        storeIdempotency(idempotencyKey, "/internal/funds/reservations/" + reservationId + "/commit", result);
        return result;
    }

    /**
     * Idempotent wrapper: release + idempotency store u jednoj transakciji.
     */
    @Transactional
    public ReleaseFundsResponse releaseIdempotent(String idempotencyKey, String reservationId,
                                                  ReleaseFundsRequest req) {
        Optional<rs.raf.banka2_bek.internalapi.model.InternalRequest> cached =
                idempotencyService.findCached(idempotencyKey);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getResponseBody(), ReleaseFundsResponse.class);
            } catch (Exception e) {
                log.warn("Idempotency deserialization failed for key {}: {}", idempotencyKey, e.getMessage());
            }
        }
        ReleaseFundsResponse result = release(reservationId, req);
        storeIdempotency(idempotencyKey, "/internal/funds/reservations/" + reservationId + "/release", result);
        return result;
    }

    /**
     * Idempotent wrapper: transfer + idempotency store u jednoj transakciji.
     */
    @Transactional
    public TransferFundsResponse transferIdempotent(String idempotencyKey, TransferFundsRequest req) {
        Optional<rs.raf.banka2_bek.internalapi.model.InternalRequest> cached =
                idempotencyService.findCached(idempotencyKey);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getResponseBody(), TransferFundsResponse.class);
            } catch (Exception e) {
                log.warn("Idempotency deserialization failed for key {}: {}", idempotencyKey, e.getMessage());
            }
        }
        TransferFundsResponse result = transfer(req);
        storeIdempotency(idempotencyKey, "/internal/funds/transfer", result);
        return result;
    }

    /**
     * Idempotent wrapper: credit + idempotency store u jednoj transakciji.
     */
    @Transactional
    public CreditFundsResponse creditIdempotent(String idempotencyKey, CreditFundsRequest req) {
        Optional<rs.raf.banka2_bek.internalapi.model.InternalRequest> cached =
                idempotencyService.findCached(idempotencyKey);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getResponseBody(), CreditFundsResponse.class);
            } catch (Exception e) {
                log.warn("Idempotency deserialization failed for key {}: {}", idempotencyKey, e.getMessage());
            }
        }
        CreditFundsResponse result = credit(req);
        storeIdempotency(idempotencyKey, "/internal/funds/credit", result);
        return result;
    }

    /**
     * Idempotent wrapper: collectTax + idempotency store u jednoj transakciji.
     */
    @Transactional
    public TaxCollectResponse collectTaxIdempotent(String idempotencyKey, TaxCollectRequest req) {
        Optional<rs.raf.banka2_bek.internalapi.model.InternalRequest> cached =
                idempotencyService.findCached(idempotencyKey);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getResponseBody(), TaxCollectResponse.class);
            } catch (Exception e) {
                log.warn("Idempotency deserialization failed for key {}: {}", idempotencyKey, e.getMessage());
            }
        }
        TaxCollectResponse result = collectTax(req);
        storeIdempotency(idempotencyKey, "/internal/funds/tax-collect", result);
        return result;
    }

    // ─── Core SAGA operacije ───────────────────────────────────────────────────

    /**
     * Rezervise sredstva na racunu: smanjuje availableBalance, povecava reservedAmount.
     */
    @Transactional
    public ReserveFundsResponse reserve(ReserveFundsRequest req) {
        // 1. Pesimisticki lock racuna
        Account account = accountRepository.findForUpdateById(req.accountId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Racun ne postoji: " + req.accountId()));

        // 2. Validacija valute
        if (!account.getCurrency().getCode().equals(req.currencyCode())) {
            throw new IllegalArgumentException(
                    "Valuta racuna (" + account.getCurrency().getCode()
                            + ") ne odgovara zahtevanoj (" + req.currencyCode() + ")");
        }

        // 3. Validacija iznosa
        if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Iznos mora biti pozitivan");
        }

        // 4. Provjera raspolozivih sredstava
        if (account.getAvailableBalance().compareTo(req.amount()) < 0) {
            throw new IllegalStateException(
                    "Nedovoljno raspolozivih sredstava na racunu " + req.accountId()
                            + ": raspolozivo " + account.getAvailableBalance()
                            + ", potrebno " + req.amount());
        }

        // 5. Azuriranje stanja racuna
        account.setAvailableBalance(account.getAvailableBalance().subtract(req.amount()));
        account.setReservedAmount(account.getReservedAmount().add(req.amount()));
        accountRepository.save(account);

        // 6. Kreiranje rezervacije
        FundReservation reservation = new FundReservation();
        reservation.setReservationId(UUID.randomUUID().toString());
        reservation.setAccountId(req.accountId());
        reservation.setAmount(req.amount());
        reservation.setCommittedAmount(BigDecimal.ZERO);
        reservation.setCurrencyCode(req.currencyCode());
        reservation.setStatus(FundReservationStatus.RESERVED);
        fundReservationRepository.save(reservation);

        // 7. Vrati odgovor
        return new ReserveFundsResponse(
                reservation.getReservationId(),
                req.accountId(),
                req.amount(),
                account.getAvailableBalance());
    }

    /**
     * Naplacuje (deo) rezervacije: smanjuje balance i reservedAmount.
     */
    @Transactional
    public CommitFundsResponse commit(String reservationId, CommitFundsRequest req) {
        // 1. Pesimisticki lock rezervacije
        FundReservation reservation = fundReservationRepository
                .findByReservationIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Rezervacija ne postoji: " + reservationId));

        // 2. Provjera statusa rezervacije
        if (reservation.getStatus() != FundReservationStatus.RESERVED) {
            throw new IllegalStateException(
                    "Rezervacija nije aktivna: " + reservationId
                            + " (status: " + reservation.getStatus() + ")");
        }

        // 3. Ukupni iznos za skidanje (amount + komisija)
        BigDecimal commission = req.commission() != null ? req.commission() : BigDecimal.ZERO;
        BigDecimal settle = req.amount().add(commission);

        // 4. Validacija: committed + settle ne sme preci rezervisani iznos
        BigDecimal newCommitted = reservation.getCommittedAmount().add(settle);
        // Tolerancija za zaokruzivanje: 0.0001
        BigDecimal tolerance = new BigDecimal("0.0001");
        if (newCommitted.subtract(reservation.getAmount()).compareTo(tolerance) > 0) {
            throw new IllegalStateException(
                    "Commit iznos (" + settle + ") premasuje preostalu rezervaciju "
                            + reservation.getAmount().subtract(reservation.getCommittedAmount()));
        }

        // 5. Pesimisticki lock racuna
        Account account = accountRepository.findForUpdateById(reservation.getAccountId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Racun rezervacije ne postoji: " + reservation.getAccountId()));

        // 6. Skidanje sa racuna (balance i reservedAmount)
        account.setBalance(account.getBalance().subtract(settle));
        account.setReservedAmount(account.getReservedAmount().subtract(settle));
        // availableBalance je vec umanjen pri reserve — ne dira se
        accountRepository.save(account);

        // 7. Azuriranje rezervacije
        reservation.setCommittedAmount(newCommitted);
        // Ako je committed dostigao amount (sa tolerancijom), zatvori rezervaciju
        if (reservation.getAmount().subtract(newCommitted).compareTo(tolerance) <= 0) {
            reservation.setStatus(FundReservationStatus.COMMITTED);
        }
        fundReservationRepository.save(reservation);

        // 8. Kreditovanje korisnika (beneficiary) ako je naveden
        if (req.beneficiaryAccountId() != null) {
            Account beneficiary = accountRepository.findForUpdateById(req.beneficiaryAccountId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Beneficiary racun ne postoji: " + req.beneficiaryAccountId()));
            beneficiary.setBalance(beneficiary.getBalance().add(req.amount()));
            beneficiary.setAvailableBalance(beneficiary.getAvailableBalance().add(req.amount()));
            accountRepository.save(beneficiary);

            // Audit: debit na account, credit na beneficiary
            saveDebitTransaction(account, settle, req.description());
            saveCreditTransaction(beneficiary, req.amount(), req.description());
        } else {
            // Audit: samo debit na account (novac napusta rezervisani racun ka trzistu/banci)
            saveDebitTransaction(account, settle, req.description());
        }

        // 9. Provizija se kreditira bankinom BANK_TRADING racunu u valuti rezervacije.
        //    (Pre 2c provizija je bila debitovana sa account-a ali nigde kreditovana.)
        creditBankCommission(account.getCurrency().getCode(), commission);

        return new CommitFundsResponse(
                reservationId,
                reservation.getCommittedAmount(),
                account.getBalance(),
                reservation.getAmount().subtract(reservation.getCommittedAmount())
                        .max(BigDecimal.ZERO));
    }

    /**
     * Oslobadja preostali rezervisani iznos natrag na availableBalance.
     * Idempotentno: ako je status vec RELEASED ili COMMITTED, vraca no-op odgovor.
     */
    @Transactional
    public ReleaseFundsResponse release(String reservationId, ReleaseFundsRequest req) {
        // 1. Pesimisticki lock rezervacije
        FundReservation reservation = fundReservationRepository
                .findByReservationIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Rezervacija ne postoji: " + reservationId));

        // 2. Idempotentnost: ako je status vec terminalan, vrati no-op
        //    (sa stvarnim raspolozivim stanjem racuna, ne null — caller ga cita bez NPE)
        if (reservation.getStatus() != FundReservationStatus.RESERVED) {
            BigDecimal currentAvailable = accountRepository.findById(reservation.getAccountId())
                    .map(Account::getAvailableBalance)
                    .orElse(BigDecimal.ZERO);
            return new ReleaseFundsResponse(reservationId, BigDecimal.ZERO, currentAvailable);
        }

        // 3. Preostali iznos
        BigDecimal remaining = reservation.getAmount().subtract(reservation.getCommittedAmount());

        // 4. Pesimisticki lock racuna
        Account account = accountRepository.findForUpdateById(reservation.getAccountId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Racun rezervacije ne postoji: " + reservation.getAccountId()));

        // 5. Vracanje raspolozivih sredstava
        account.setAvailableBalance(account.getAvailableBalance().add(remaining));
        account.setReservedAmount(account.getReservedAmount().subtract(remaining));
        accountRepository.save(account);

        // 6. Zatvaranje rezervacije
        reservation.setStatus(FundReservationStatus.RELEASED);
        fundReservationRepository.save(reservation);

        return new ReleaseFundsResponse(reservationId, remaining, account.getAvailableBalance());
    }

    /**
     * Direktan prenos novca izmedju dva racuna (ista valuta).
     * Bez rezervacije — atomski debit/credit.
     */
    @Transactional
    public TransferFundsResponse transfer(TransferFundsRequest req) {
        // 1. Pesimisticki lock oba racuna (uvek isti redosled po ID-u da bi se izbeglo deadlock)
        Long minId = Math.min(req.fromAccountId(), req.toAccountId());
        Long maxId = Math.max(req.fromAccountId(), req.toAccountId());
        Account first = accountRepository.findForUpdateById(minId)
                .orElseThrow(() -> new IllegalArgumentException("Racun ne postoji: " + minId));
        Account second = accountRepository.findForUpdateById(maxId)
                .orElseThrow(() -> new IllegalArgumentException("Racun ne postoji: " + maxId));

        Account from = first.getId().equals(req.fromAccountId()) ? first : second;
        Account to = first.getId().equals(req.toAccountId()) ? first : second;

        // 2. Validacija valute (oba racuna moraju biti u istoj valuti)
        if (!from.getCurrency().getCode().equals(req.currencyCode())) {
            throw new IllegalArgumentException(
                    "Valuta from-racuna (" + from.getCurrency().getCode()
                            + ") ne odgovara zahtevanoj (" + req.currencyCode() + ")");
        }
        if (!to.getCurrency().getCode().equals(req.currencyCode())) {
            throw new IllegalArgumentException(
                    "Valuta to-racuna (" + to.getCurrency().getCode()
                            + ") ne odgovara zahtevanoj (" + req.currencyCode() + ")");
        }

        // 3. Validacija iznosa
        if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Iznos mora biti pozitivan");
        }

        // 4. Provizija (opciono) — debituje se sa from-a uz amount, kreditira banci
        BigDecimal commission = req.commission() != null ? req.commission() : BigDecimal.ZERO;
        if (commission.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Provizija ne sme biti negativna");
        }
        BigDecimal debitTotal = req.amount().add(commission);

        // 5. Provjera raspolozivih sredstava (amount + provizija)
        if (from.getAvailableBalance().compareTo(debitTotal) < 0) {
            throw new IllegalStateException(
                    "Nedovoljno raspolozivih sredstava na racunu " + req.fromAccountId()
                            + ": raspolozivo " + from.getAvailableBalance()
                            + ", potrebno " + debitTotal);
        }

        // 6. Debit from (amount + provizija), credit to (amount)
        from.setBalance(from.getBalance().subtract(debitTotal));
        from.setAvailableBalance(from.getAvailableBalance().subtract(debitTotal));
        to.setBalance(to.getBalance().add(req.amount()));
        to.setAvailableBalance(to.getAvailableBalance().add(req.amount()));
        accountRepository.save(from);
        accountRepository.save(to);

        // 7. Audit transakcije
        saveDebitTransaction(from, debitTotal, req.description());
        saveCreditTransaction(to, req.amount(), req.description());

        // 8. Provizija se kreditira bankinom BANK_TRADING racunu u valuti prenosa.
        creditBankCommission(req.currencyCode(), commission);

        return new TransferFundsResponse(
                req.fromAccountId(),
                req.toAccountId(),
                req.amount(),
                from.getBalance(),
                to.getBalance());
    }

    /**
     * Jednostrani kredit racuna bez debit kontra-strane.
     * Verno modelu monolita: trziste je apstraktan izvor novca — SELL prihod i
     * dividende se kreditiraju korisnikovom racunu bez ijednog debit-a. Opciona
     * provizija ide bankinom BANK_TRADING racunu u {@code currencyCode}.
     */
    @Transactional
    public CreditFundsResponse credit(CreditFundsRequest req) {
        // 1. Validacija iznosa
        if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Iznos mora biti pozitivan");
        }
        BigDecimal commission = req.commission() != null ? req.commission() : BigDecimal.ZERO;
        if (commission.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Provizija ne sme biti negativna");
        }

        // 2. Pesimisticki lock racuna
        Account account = accountRepository.findForUpdateById(req.accountId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Racun ne postoji: " + req.accountId()));

        // 3. Validacija valute
        if (!account.getCurrency().getCode().equals(req.currencyCode())) {
            throw new IllegalArgumentException(
                    "Valuta racuna (" + account.getCurrency().getCode()
                            + ") ne odgovara zahtevanoj (" + req.currencyCode() + ")");
        }

        // 4. Kreditovanje racuna
        account.setBalance(account.getBalance().add(req.amount()));
        account.setAvailableBalance(account.getAvailableBalance().add(req.amount()));
        accountRepository.save(account);

        // 5. Audit: credit na racun (novac dolazi sa trzista)
        saveCreditTransaction(account, req.amount(), req.description());

        // 6. Provizija se kreditira bankinom BANK_TRADING racunu.
        creditBankCommission(req.currencyCode(), commission);

        return new CreditFundsResponse(account.getId(), req.amount(), account.getBalance());
    }

    /**
     * Naplata poreza na kapitalnu dobit: debit RSD racuna klijenta, credit
     * drzavnog RSD racuna.
     *
     * Verno monolitovom {@code TaxService.collectTaxFromUser}:
     *  - placa racun klijenta: bira prvi RSD racun (status ACTIVE, sortirano po
     *    availableBalance opadajuce) cija je {@code balance >= amount};
     *  - drzavni racun: RSD racun Firme cija je registracioni broj
     *    {@code state.registration-number};
     *  - ako klijent nema RSD racun sa dovoljno sredstava (ili drzavni racun ne
     *    postoji), naplata se PRESKACE — vraca {@code collected=false}, BEZ
     *    bacanja izuzetka (monolit isto loguje warning i nastavlja).
     */
    @Transactional
    public TaxCollectResponse collectTax(TaxCollectRequest req) {
        // 1. Validacija iznosa
        if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Iznos mora biti pozitivan");
        }

        // 2. Drzavni RSD racun (Republika Srbija kao Firma)
        Optional<Account> stateAccountOpt = accountRepository
                .findBankAccountForUpdateByCurrency(stateRegistrationNumber, "RSD");
        if (stateAccountOpt.isEmpty()) {
            log.warn("Drzavni RSD racun ne postoji — naplata poreza preskocena za klijenta {}",
                    req.payerClientId());
            return new TaxCollectResponse(req.payerClientId(), BigDecimal.ZERO, false);
        }

        // 3. Klijentov RSD racun sa dovoljno sredstava (replika TaxService logike)
        List<Account> payerAccounts = accountRepository
                .findByClientIdAndStatusOrderByAvailableBalanceDesc(
                        req.payerClientId(), AccountStatus.ACTIVE);
        Optional<Account> payerRsdOpt = payerAccounts.stream()
                .filter(a -> "RSD".equals(a.getCurrency().getCode()))
                .filter(a -> a.getBalance().compareTo(req.amount()) >= 0)
                .findFirst();
        if (payerRsdOpt.isEmpty()) {
            log.warn("Klijent {} nema RSD racun sa dovoljno sredstava — naplata poreza preskocena",
                    req.payerClientId());
            return new TaxCollectResponse(req.payerClientId(), BigDecimal.ZERO, false);
        }

        // 4. Pesimisticki lock klijentovog racuna (ascending-id ordering vs deadlock)
        Account stateAccount = stateAccountOpt.get();
        Account payerAccount = accountRepository.findForUpdateById(payerRsdOpt.get().getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Racun klijenta ne postoji: " + payerRsdOpt.get().getId()));

        // 5. Re-provera balansa pod lock-om (drugi worker je mogao da potrosi)
        if (payerAccount.getBalance().compareTo(req.amount()) < 0) {
            log.warn("Klijent {} nema dovoljno sredstava pod lock-om — naplata poreza preskocena",
                    req.payerClientId());
            return new TaxCollectResponse(req.payerClientId(), BigDecimal.ZERO, false);
        }

        // 6. Debit klijent, credit drzava
        payerAccount.setBalance(payerAccount.getBalance().subtract(req.amount()));
        payerAccount.setAvailableBalance(
                payerAccount.getAvailableBalance().subtract(req.amount()));
        accountRepository.save(payerAccount);

        stateAccount.setBalance(stateAccount.getBalance().add(req.amount()));
        stateAccount.setAvailableBalance(stateAccount.getAvailableBalance().add(req.amount()));
        accountRepository.save(stateAccount);

        // 7. Audit transakcije
        saveDebitTransaction(payerAccount, req.amount(), req.description());
        saveCreditTransaction(stateAccount, req.amount(), req.description());

        return new TaxCollectResponse(req.payerClientId(), req.amount(), true);
    }

    // ─── Pomocne metode ───────────────────────────────────────────────────────

    /**
     * Kreditira proviziju bankinom BANK_TRADING racunu u datoj valuti.
     * No-op ako je {@code commission <= 0}. Razresava racun isto kao
     * {@code OrderExecutionService}/{@code InvestmentFundService}
     * ({@code findFirstByAccountCategoryAndCurrency_Code(BANK_TRADING, ...)}).
     */
    private void creditBankCommission(String currencyCode, BigDecimal commission) {
        if (commission == null || commission.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Account bankAccount = accountRepository
                .findFirstByAccountCategoryAndCurrency_Code(
                        AccountCategory.BANK_TRADING, currencyCode)
                .orElseThrow(() -> new IllegalStateException(
                        "Bankin trading racun ne postoji u valuti " + currencyCode));
        bankAccount.setBalance(bankAccount.getBalance().add(commission));
        bankAccount.setAvailableBalance(bankAccount.getAvailableBalance().add(commission));
        accountRepository.save(bankAccount);
        saveCreditTransaction(bankAccount, commission, "Provizija (interni settlement)");
    }

    private void saveDebitTransaction(Account account, BigDecimal amount, String description) {
        Transaction tx = Transaction.builder()
                .account(account)
                .currency(account.getCurrency())
                .description(description)
                .debit(amount)
                .credit(BigDecimal.ZERO)
                .reserved(BigDecimal.ZERO)
                .reservedUsed(BigDecimal.ZERO)
                .balanceAfter(account.getBalance())
                .availableAfter(account.getAvailableBalance())
                .build();
        transactionRepository.save(tx);
    }

    private void saveCreditTransaction(Account account, BigDecimal amount, String description) {
        Transaction tx = Transaction.builder()
                .account(account)
                .currency(account.getCurrency())
                .description(description)
                .debit(BigDecimal.ZERO)
                .credit(amount)
                .reserved(BigDecimal.ZERO)
                .reservedUsed(BigDecimal.ZERO)
                .balanceAfter(account.getBalance())
                .availableAfter(account.getAvailableBalance())
                .build();
        transactionRepository.save(tx);
    }

    private void storeIdempotency(String key, String endpoint, Object result) {
        String body;
        try {
            body = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            // Serijalizacija MORA uspeti: idempotency kes mora biti konzistentan sa
            // izvrsenom operacijom. Propagiramo (unchecked) da se cela @Transactional
            // operacija rollback-uje — bez divergencije commit-ovano stanje vs kes.
            throw new RuntimeException("Idempotency serijalizacija nije uspela za kljuc " + key, e);
        }
        idempotencyService.store(key, endpoint, 200, body);
    }
}
