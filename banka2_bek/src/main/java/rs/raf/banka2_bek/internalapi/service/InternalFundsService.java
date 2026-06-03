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
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.exchange.CurrencyConversionService;
import rs.raf.banka2_bek.internalapi.model.FundReservation;
import rs.raf.banka2_bek.internalapi.model.FundReservationStatus;
import rs.raf.banka2_bek.internalapi.repository.FundReservationRepository;
import rs.raf.banka2_bek.transaction.model.Transaction;
import rs.raf.banka2_bek.transaction.repository.TransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /**
     * R1-712: tolerancija pri commit-u rezervacije (zaokruzivanje) — committed iznos
     * sme da premasi rezervisani najvise za ovoliko (apsolutno) pre nego sto se odbije
     * kao "premasuje rezervaciju". Pokriva sub-cent BigDecimal scale/banker's-rounding sum.
     */
    private static final BigDecimal COMMIT_ROUNDING_TOLERANCE = new BigDecimal("0.0001");

    /**
     * R1-712: relativna FX-drift tolerancija (1%) za BE-INT-07 integrity check
     * (creditAmount ≈ debitAmount × expectedRate). Hvata inverzni kurs / off-by-decimal,
     * propusta legitimne rounding razlike. Sinhrono sa notif-svc istom konstantom (R1-747).
     */
    private static final BigDecimal FX_DRIFT_TOLERANCE = new BigDecimal("0.01");

    private final AccountRepository accountRepository;
    private final FundReservationRepository fundReservationRepository;
    private final TransactionRepository transactionRepository;
    private final InternalIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final CurrencyConversionService currencyConversionService;

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
                                ObjectMapper objectMapper,
                                CurrencyConversionService currencyConversionService) {
        this.accountRepository = accountRepository;
        this.fundReservationRepository = fundReservationRepository;
        this.transactionRepository = transactionRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.currencyConversionService = currencyConversionService;
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
     * Idempotent wrapper: debit + idempotency store u jednoj transakciji.
     */
    @Transactional
    public DebitFundsResponse debitIdempotent(String idempotencyKey, DebitFundsRequest req) {
        Optional<rs.raf.banka2_bek.internalapi.model.InternalRequest> cached =
                idempotencyService.findCached(idempotencyKey);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getResponseBody(), DebitFundsResponse.class);
            } catch (Exception e) {
                log.warn("Idempotency deserialization failed for key {}: {}", idempotencyKey, e.getMessage());
            }
        }
        DebitFundsResponse result = debit(req);
        storeIdempotency(idempotencyKey, "/internal/funds/debit", result);
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
        // R1-712: tolerancija za zaokruzivanje (imenovana konstanta).
        BigDecimal tolerance = COMMIT_ROUNDING_TOLERANCE;
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
     * Direktan prenos novca izmedju dva racuna.
     *
     * <p>Cross-currency-sposoban: from-racun se debituje za {@code debitAmount}
     * (uvek u SOPSTVENOJ valuti from-racuna), to-racun se kreditira za
     * {@code creditAmount} (u NJEGOVOJ sopstvenoj valuti). Pozivalac je vec
     * uradio FX matematiku i dostavlja tacne iznose; za prenos iste valute je
     * {@code debitAmount == creditAmount}. Opciona provizija se kreditira
     * bankinom BANK_TRADING racunu u {@code commissionCurrency}.
     *
     * <p>Bez rezervacije — atomski debit/credit. Nedovoljna sredstva na
     * debit-nozi → {@link IllegalStateException} (→ HTTP 409).
     */
    @Transactional
    public TransferFundsResponse transfer(TransferFundsRequest req) {
        // 1. Validacija iznosa
        if (req.debitAmount() == null || req.debitAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Debit iznos mora biti pozitivan");
        }
        if (req.creditAmount() == null || req.creditAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit iznos mora biti pozitivan");
        }
        BigDecimal commission = req.commission() != null ? req.commission() : BigDecimal.ZERO;
        if (commission.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Provizija ne sme biti negativna");
        }

        // 1b. BE-INT-07 FX integrity check (opciono): ako je {@code expectedRate}
        // dostavljen, validira da {@code creditAmount ≈ debitAmount * expectedRate}
        // unutar 1% tolerancije od {@code |creditAmount|}. Tolerancija pokriva
        // rounding razlike (BigDecimal scale, banker's rounding, decimalna mesta
        // razlicitih FX feeda), ali uhvata stvarne racunske bug-ove (npr. inverzni
        // kurs, pogresna valuta, off-by-decimal). Pozivalac (trading-service) treba
        // da postavi {@code expectedRate} kada je {@code debitAmount.scale() != creditAmount.scale()}
        // ili kad su valute razlicite (cross-currency transfer). {@code null} preskoci
        // proveru za back-compat sa starim caller-ima.
        if (req.expectedRate() != null) {
            if (req.expectedRate().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(
                        "expectedRate mora biti pozitivan: " + req.expectedRate());
            }
            BigDecimal computedCredit = req.debitAmount()
                    .multiply(req.expectedRate())
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal drift = computedCredit.subtract(req.creditAmount()).abs();
            BigDecimal allowedDrift = req.creditAmount().abs()
                    .multiply(FX_DRIFT_TOLERANCE);
            if (drift.compareTo(allowedDrift) > 0) {
                throw new IllegalArgumentException(
                        "FX rate drift exceeds 1% tolerance: expected creditAmount "
                                + computedCredit + " (= debitAmount " + req.debitAmount()
                                + " * expectedRate " + req.expectedRate() + "), got "
                                + req.creditAmount() + " (drift " + drift + " > allowed "
                                + allowedDrift + ")");
            }
        }

        // 2. Pesimisticki lock oba racuna (uvek isti redosled po ID-u da bi se izbeglo deadlock)
        Long minId = Math.min(req.fromAccountId(), req.toAccountId());
        Long maxId = Math.max(req.fromAccountId(), req.toAccountId());
        Account first = accountRepository.findForUpdateById(minId)
                .orElseThrow(() -> new IllegalArgumentException("Racun ne postoji: " + minId));
        Account second = accountRepository.findForUpdateById(maxId)
                .orElseThrow(() -> new IllegalArgumentException("Racun ne postoji: " + maxId));

        Account from = first.getId().equals(req.fromAccountId()) ? first : second;
        Account to = first.getId().equals(req.toAccountId()) ? first : second;

        // 3. Provjera raspolozivih sredstava na from-nozi (debitAmount u valuti from-racuna)
        if (from.getAvailableBalance().compareTo(req.debitAmount()) < 0) {
            throw new IllegalStateException(
                    "Nedovoljno raspolozivih sredstava na racunu " + req.fromAccountId()
                            + ": raspolozivo " + from.getAvailableBalance()
                            + ", potrebno " + req.debitAmount());
        }

        // 4. Debit from (debitAmount, valuta from-racuna), credit to (creditAmount, valuta to-racuna)
        from.setBalance(from.getBalance().subtract(req.debitAmount()));
        from.setAvailableBalance(from.getAvailableBalance().subtract(req.debitAmount()));
        to.setBalance(to.getBalance().add(req.creditAmount()));
        to.setAvailableBalance(to.getAvailableBalance().add(req.creditAmount()));
        accountRepository.save(from);
        accountRepository.save(to);

        // 5. Audit transakcije (debit-noga u valuti from-racuna, credit-noga u valuti to-racuna)
        saveDebitTransaction(from, req.debitAmount(), req.description());
        saveCreditTransaction(to, req.creditAmount(), req.description());

        // 6. Provizija se kreditira bankinom BANK_TRADING racunu u commissionCurrency.
        creditBankCommission(req.commissionCurrency(), commission);

        return new TransferFundsResponse(
                req.fromAccountId(),
                req.toAccountId(),
                req.debitAmount(),
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
     * Jednostrani debit racuna bez credit kontra-strane (simetricno {@link #credit}).
     * Verno modelu monolita: trziste je apstraktan ponor novca — option exercise
     * CALL i pocetna uplata marginskog racuna zaduzuju racun bez ijednog credit-a.
     * Opciona provizija ide bankinom BANK_TRADING racunu u {@code currencyCode}.
     * Nedovoljna raspoloziva sredstva na racunu → {@link IllegalStateException}
     * (→ HTTP 409), isto kao {@link #reserve}/{@link #transfer}/{@link #commit}.
     */
    @Transactional
    public DebitFundsResponse debit(DebitFundsRequest req) {
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

        // 4. Provjera raspolozivih sredstava (nedovoljno → 409, kao reserve/transfer)
        if (account.getAvailableBalance().compareTo(req.amount()) < 0) {
            throw new IllegalStateException(
                    "Nedovoljno raspolozivih sredstava na racunu " + req.accountId()
                            + ": raspolozivo " + account.getAvailableBalance()
                            + ", potrebno " + req.amount());
        }

        // 5. Zaduzivanje racuna
        account.setBalance(account.getBalance().subtract(req.amount()));
        account.setAvailableBalance(account.getAvailableBalance().subtract(req.amount()));
        accountRepository.save(account);

        // 6. Audit: debit na racun (novac napusta racun ka trzistu/margin ledger-u)
        saveDebitTransaction(account, req.amount(), req.description());

        // 7. Provizija se kreditira bankinom BANK_TRADING racunu.
        creditBankCommission(req.currencyCode(), commission);

        return new DebitFundsResponse(account.getId(), req.amount(), account.getBalance());
    }

    /**
     * Naplata poreza na kapitalnu dobit: debit racuna klijenta, credit drzavnog
     * RSD racuna. {@code req.amount()} je iskazan u RSD (drzava ima samo RSD racun,
     * spec Celina 3 Napomena 2).
     *
     * <p>Izbor placajuceg racuna (spec §522-527):
     * <ol>
     *   <li>Prvo se trazi <b>RSD racun</b> sa dovoljno sredstava — debit je nativan
     *       (bez FX-a), drzava dobija isti iznos. Ovo je glavni put (§522: dobit lezi
     *       na racunu, porez se skida sa istog).</li>
     *   <li><b>P0-B3 (EUR-only fix):</b> ako klijent NEMA RSD racun sa dovoljno
     *       sredstava ali ima racun u stranoj valuti (npr. EUR-only portfolio cija je
     *       dobit ostvarena u EUR), porez se naplacuje iz tog racuna. RSD iznos poreza
     *       se konvertuje u valutu racuna ({@code convert(amount, RSD, currency)} —
     *       srednji kurs, bez provizije, spec §527 "kao u menjacnici ali bez provizije")
     *       i nativni ekvivalent se skida sa racuna; drzava i dalje dobija RSD iznos.
     *       Pre P0-B3 ovaj klijent NIKAD nije bio oporezovan ({@code collected=false}).</li>
     * </ol>
     *
     * <p>Conservation: drzavni RSD racun uvek dobija {@code req.amount()} (RSD);
     * placajuci racun gubi nativni FX-ekvivalent. Knjige su balansirane (cross-currency
     * settlement po srednjem kursu — ista ekonomija kao menjacnica).
     *
     * <p>Ako ni jedan racun (RSD ili strani, posle FX-a) nema dovoljno sredstava, ili
     * drzavni racun ne postoji, naplata se PRESKACE — {@code collected=false}, BEZ
     * bacanja izuzetka (sledeci mesecni obracun ce pokusati ponovo).
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

        // 3. Izbor placajuceg racuna: RSD-first, pa strani uz FX (P0-B3).
        List<Account> payerAccounts = accountRepository
                .findByClientIdAndStatusOrderByAvailableBalanceDesc(
                        req.payerClientId(), AccountStatus.ACTIVE);

        PayerSelection selection = selectPayerAccount(payerAccounts, req.amount());
        if (selection == null) {
            log.warn("Klijent {} nema racun (RSD ni strani uz FX) sa dovoljno sredstava — "
                    + "naplata poreza preskocena", req.payerClientId());
            return new TaxCollectResponse(req.payerClientId(), BigDecimal.ZERO, false);
        }

        // 4. Pesimisticki lock klijentovog racuna (ascending-id ordering vs deadlock)
        Account stateAccount = stateAccountOpt.get();
        Account payerAccount = accountRepository.findForUpdateById(selection.account().getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Racun klijenta ne postoji: " + selection.account().getId()));

        // 5. Nativni iznos za debit (RSD na RSD racunu; FX-ekvivalent na stranom).
        //    Re-konvertujemo pod lock-om da koristimo isti racun-id (valuta se ne menja).
        String payerCurrency = payerAccount.getCurrency().getCode();
        BigDecimal nativeDebit = "RSD".equalsIgnoreCase(payerCurrency)
                ? req.amount()
                : currencyConversionService.convert(req.amount(), "RSD", payerCurrency);

        // 6. Re-provera balansa pod lock-om (drugi worker je mogao da potrosi)
        if (payerAccount.getBalance().compareTo(nativeDebit) < 0) {
            log.warn("Klijent {} nema dovoljno sredstava pod lock-om — naplata poreza preskocena",
                    req.payerClientId());
            return new TaxCollectResponse(req.payerClientId(), BigDecimal.ZERO, false);
        }

        // 7. Debit klijent (nativna valuta), credit drzava (RSD)
        payerAccount.setBalance(payerAccount.getBalance().subtract(nativeDebit));
        payerAccount.setAvailableBalance(
                payerAccount.getAvailableBalance().subtract(nativeDebit));
        accountRepository.save(payerAccount);

        stateAccount.setBalance(stateAccount.getBalance().add(req.amount()));
        stateAccount.setAvailableBalance(stateAccount.getAvailableBalance().add(req.amount()));
        accountRepository.save(stateAccount);

        // 8. Audit transakcije — debit u nativnoj valuti, credit drzavi u RSD
        String debitDesc = "RSD".equalsIgnoreCase(payerCurrency)
                ? req.description()
                : req.description() + " (FX: " + req.amount().toPlainString() + " RSD)";
        saveDebitTransaction(payerAccount, nativeDebit, debitDesc);
        saveCreditTransaction(stateAccount, req.amount(), req.description());

        // Drzava dobija RSD iznos; collectedAmount izvestava RSD (sto je porez).
        return new TaxCollectResponse(req.payerClientId(), req.amount(), true);
    }

    /** Rezultat izbora placajuceg racuna za porez. */
    private record PayerSelection(Account account, boolean fx) {}

    /**
     * Bira placajuci racun za porez (iznos je u RSD):
     * <ol>
     *   <li>prvi RSD racun sa {@code balance >= amount} (bez FX-a);</li>
     *   <li>P0-B3: ako nema RSD racuna sa dovoljno, prvi strani racun ciji
     *       FX-konvertovani RSD iznos pokriva {@code balance} (porez se naplacuje
     *       iz valute u kojoj je dobit ostvarena).</li>
     * </ol>
     * Vraca {@code null} ako nijedan racun ne pokriva porez.
     */
    private PayerSelection selectPayerAccount(List<Account> payerAccounts, BigDecimal amountRsd) {
        if (payerAccounts == null || payerAccounts.isEmpty()) {
            return null;
        }
        // 1. RSD-first (glavni put, bez FX exposure).
        Optional<Account> rsd = payerAccounts.stream()
                .filter(a -> a.getCurrency() != null && "RSD".equalsIgnoreCase(a.getCurrency().getCode()))
                .filter(a -> a.getBalance().compareTo(amountRsd) >= 0)
                .findFirst();
        if (rsd.isPresent()) {
            return new PayerSelection(rsd.get(), false);
        }
        // 2. P0-B3: strani racun uz FX (EUR-only dobit). Konvertuj RSD porez u valutu
        //    racuna i proveri pokrivenost; preskoci racune sa nepodrzanom valutom.
        for (Account a : payerAccounts) {
            if (a.getCurrency() == null) {
                continue;
            }
            String code = a.getCurrency().getCode();
            if ("RSD".equalsIgnoreCase(code)) {
                continue; // RSD racun bez dovoljno sredstava — vec proveren iznad
            }
            try {
                BigDecimal nativeNeeded = currencyConversionService.convert(amountRsd, "RSD", code);
                if (a.getBalance().compareTo(nativeNeeded) >= 0) {
                    return new PayerSelection(a, true);
                }
            } catch (RuntimeException fxEx) {
                log.warn("FX RSD->{} nedostupan za izbor poreskog racuna: {}", code, fxEx.getMessage());
            }
        }
        return null;
    }

    // ─── Pomocne metode ───────────────────────────────────────────────────────

    /**
     * Kreditira proviziju bankinom BANK_TRADING racunu u datoj valuti.
     * No-op ako je {@code commission <= 0}.
     *
     * <p>P0-B3 (lost-update fix): balance bankinog racuna se citanje-modifikacija-
     * pisanje azurira, a Account nema {@code @Version}. Bez locka konkurentni
     * settlement-i (vise BUY/transfer fill-ova na 1 replici) izgube proviziju.
     * Zato razresavamo racun PESSIMISTIC_WRITE lookup-om
     * ({@code findFirstByAccountCategoryAndCurrencyCodeForUpdate}) umesto ne-locking
     * {@code findFirstByAccountCategoryAndCurrency_Code} — paritet sa {@code collectTax}
     * i ostatkom codebase-a koji bankine/state racune cita pod lock-om.
     */
    private void creditBankCommission(String currencyCode, BigDecimal commission) {
        if (commission == null || commission.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Account bankAccount = accountRepository
                .findByAccountCategoryAndCurrencyCodeForUpdate(
                        AccountCategory.BANK_TRADING, currencyCode)
                .stream().findFirst()
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
