package rs.raf.trading.otc.saga.support;

import rs.raf.banka2.contracts.internal.CommitFundsRequest;
import rs.raf.banka2.contracts.internal.CommitFundsResponse;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.CreditFundsResponse;
import rs.raf.banka2.contracts.internal.DebitFundsRequest;
import rs.raf.banka2.contracts.internal.DebitFundsResponse;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.ReleaseFundsRequest;
import rs.raf.banka2.contracts.internal.ReleaseFundsResponse;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.banka2.contracts.internal.TransferFundsResponse;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verodostojni in-memory dvojnik {@link BankaCoreClient} za W2 SAGA invarijant
 * testove (Docker daemon nedostupan → bez Testcontainers banka-core).
 *
 * <p>Replicira konzervaciono-korektnu semantiku banka-core {@code
 * InternalFundsService}: novac postoji samo kao {@code balance} po racunu;
 * {@code reserved} je hold koji smanjuje raspolozivo stanje ({@code
 * availableBalance = balance - reserved}) ali NE menja ukupnu masu novca.
 * Suma {@code balance} preko svih racuna ({@link #totalMoney()}) sme da se
 * promeni SAMO kroz {@code credit} (jednostrani priliv споља) ili {@code debit}
 * (jednostrani odliv), nikako kroz reserve/commit/release/transfer interno —
 * sto je tacno invarijanta I1 koju testovi proveravaju nad ovim dvojnikom.
 *
 * <p>Idempotency kljuc se ne kesira (banka-core ga koristi za replay; ovde
 * orchestrator ne radi retry forward poziva, a kompenzatori su prirodno
 * idempotentni kroz status rezervacije), ali {@link #reserveFunds} je
 * deterministički u ID dodeli, a {@link #releaseFunds} no-op-uje na vec
 * oslobodjenoj rezervaciji (idempotentno).
 *
 * <p>{@code extends BankaCoreClient} sa {@code super(null)} — sve koriscene
 * metode su override-ovane, pa se {@code null} RestClient nikad ne dotice.
 */
public class FakeBankaCoreClient extends BankaCoreClient {

    /** Stanje jednog racuna: valuta + ukupni saldo + trenutno rezervisano. */
    public static final class Acct {
        final String currency;
        BigDecimal balance;
        BigDecimal reserved;

        Acct(String currency, BigDecimal balance, BigDecimal reserved) {
            this.currency = currency;
            this.balance = balance;
            this.reserved = reserved;
        }

        BigDecimal available() {
            return balance.subtract(reserved);
        }
    }

    private enum ResState { RESERVED, COMMITTED, RELEASED }

    private static final class Reservation {
        final Long accountId;
        final BigDecimal amount;
        ResState state;

        Reservation(Long accountId, BigDecimal amount) {
            this.accountId = accountId;
            this.amount = amount;
            this.state = ResState.RESERVED;
        }
    }

    private final Map<Long, Acct> accounts = new LinkedHashMap<>();
    private final Map<String, Reservation> reservations = new LinkedHashMap<>();
    /**
     * <b>N4 (P0-T2):</b> verodostojan banka-core dedup store — svaki USPESAN idempotentni
     * mutirajuci poziv (commit/credit/transfer/debit) belezi svoj kljuc. {@link
     * #isIdempotencyKeyConsumed(String)} cita ovaj skup (autoritativan read-back). Tako
     * "svet pre credit-a" (creditFunds nikad pozvan) tacno daje {@code consumed=false} za
     * F3-credit kljuc, a "svet posle credit-a" {@code consumed=true} (mozemo i rucno seed-ovati).
     */
    private final Set<String> consumedIdempotencyKeys = new LinkedHashSet<>();
    /** (userRole|userId) -> accountId, za getPreferredAccount lookup. */
    private final Map<String, Long> preferredByOwner = new LinkedHashMap<>();
    /** valuta -> bankin trading account id. */
    private final Map<String, Long> bankTradingByCcy = new LinkedHashMap<>();
    private final AtomicInteger reservationCounter = new AtomicInteger();

    /**
     * Fault toggle (P0-1 test): kad je &gt; 0, sledeci {@link #creditFunds} poziv
     * baca 5xx i dekrementuje brojac. Simulira realan banka-core blip POSLE uspelog
     * {@code commitFunds} u F3 (F3 nije atomican — commit pa credit su 2 poziva).
     */
    private final AtomicInteger failNextCreditCount = new AtomicInteger(0);
    /** Ako nije null, {@link #creditFunds} baca SAMO kad je ciljani racun ovaj. */
    private volatile Long failCreditOnlyForAccountId = null;

    public FakeBankaCoreClient() {
        super(null);
    }

    // ── seed API (poziva test pre exercise-a) ───────────────────────────────

    /** Registruje racun sa pocetnim saldom (reserved=0). */
    public void seedAccount(Long accountId, String currency, BigDecimal balance) {
        accounts.put(accountId, new Acct(currency, balance, BigDecimal.ZERO));
    }

    /** Registruje racun sa pocetnim saldom I pocetno vec rezervisanim iznosom. */
    public void seedAccount(Long accountId, String currency, BigDecimal balance, BigDecimal reserved) {
        accounts.put(accountId, new Acct(currency, balance, reserved));
    }

    /**
     * Registruje accept-time rezervaciju (ugovor je seedovan sa
     * {@code bankaCoreReservationId} pa F1 REUSE-uje umesto reserveFunds poziva).
     * Reserved deo racuna se NE uvecava ovde — pretpostavka je da je
     * {@link #seedAccount(Long, String, BigDecimal, BigDecimal)} vec ukljucio taj hold.
     */
    public void seedReservation(String reservationId, Long accountId, BigDecimal amount) {
        reservations.put(reservationId, new Reservation(accountId, amount));
    }

    /** Mapira (role,userId) -> accountId za getPreferredAccount razresavanje. */
    public void mapPreferredAccount(String userRole, Long userId, Long accountId) {
        preferredByOwner.put(userRole + "|" + userId, accountId);
    }

    /** Mapira valutu -> bankin trading racun za getBankTradingAccount. */
    public void mapBankTradingAccount(String currencyCode, Long accountId) {
        bankTradingByCcy.put(currencyCode, accountId);
    }

    /**
     * <b>N4 (P0-T2):</b> rucno oznaci idempotency kljuc kao VEC KONZUMIRAN (banka-core
     * dedup store ima zapis). Koristi se da se seed-uje "svet posle credit-a" (crash izmedju
     * uspelog creditFunds i persist(done)) bez ponovnog poziva creditFunds — recovery C3 onda
     * autoritativno vidi {@code consumed=true} → pun reverzni transfer.
     */
    public void markIdempotencyConsumed(String key) {
        consumedIdempotencyKeys.add(key);
    }

    /**
     * Naredni {@link #creditFunds} poziv (bilo koji racun) baca 5xx
     * {@link BankaCoreClientException} — simulira banka-core blip izmedju F3
     * {@code commitFunds} (vec uspeo) i {@code creditFunds}. Stanje racuna se NE
     * menja (greska se baca pre add-a), pa nastaje delimican F3: buyer debitovan,
     * prodavac NIKAD kreditiran.
     */
    public void failNextCredit() {
        failNextCreditCount.set(1);
    }

    /** Kao {@link #failNextCredit()} ali baca samo kad je ciljani credit-racun {@code accountId}. */
    public void failCreditFor(Long accountId) {
        failNextCreditCount.set(1);
        failCreditOnlyForAccountId = accountId;
    }

    // ── test introspekcija (StateSnapshot + asercije) ───────────────────────

    public BigDecimal balanceOf(Long accountId) {
        return requireAcct(accountId).balance;
    }

    public BigDecimal reservedOf(Long accountId) {
        return requireAcct(accountId).reserved;
    }

    /** Σ balance preko svih racuna — masa novca koja sme da se menja samo eksterno. */
    public BigDecimal totalMoney() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Acct a : accounts.values()) {
            sum = sum.add(a.balance);
        }
        return sum;
    }

    // ── BankaCoreClient surface (override) ──────────────────────────────────

    @Override
    public ReserveFundsResponse reserveFunds(String idempotencyKey, ReserveFundsRequest req) {
        Acct a = requireAcct(req.accountId());
        if (a.available().compareTo(req.amount()) < 0) {
            throw new BankaCoreClientException(409, "insufficient");           // SG-03 / F1 fail
        }
        a.reserved = a.reserved.add(req.amount());
        String reservationId = "res-" + reservationCounter.incrementAndGet();
        reservations.put(reservationId, new Reservation(req.accountId(), req.amount()));
        return new ReserveFundsResponse(reservationId, req.accountId(), req.amount(), a.available());
    }

    @Override
    public CommitFundsResponse commitFunds(String reservationId, String idempotencyKey,
                                           CommitFundsRequest req) {
        Reservation r = requireReservation(reservationId);
        if (r.state != ResState.RESERVED) {
            throw new BankaCoreClientException(409, "rezervacija nije RESERVED: " + reservationId);
        }
        Acct from = requireAcct(r.accountId);
        from.balance = from.balance.subtract(req.amount());
        from.reserved = from.reserved.subtract(req.amount());
        r.state = ResState.COMMITTED;
        if (req.beneficiaryAccountId() != null) {
            Acct ben = requireAcct(req.beneficiaryAccountId());
            ben.balance = ben.balance.add(req.amount());                       // ista valuta
        }
        consumedIdempotencyKeys.add(idempotencyKey);
        return new CommitFundsResponse(reservationId, req.amount(), from.balance, from.reserved);
    }

    @Override
    public ReleaseFundsResponse releaseFunds(String reservationId, String idempotencyKey,
                                             ReleaseFundsRequest req) {
        Reservation r = reservations.get(reservationId);
        if (r == null || r.state != ResState.RESERVED) {
            // idempotentno: vec oslobodjeno / commit-ovano / nepostojece -> no-op
            return new ReleaseFundsResponse(reservationId, BigDecimal.ZERO,
                    r != null ? requireAcct(r.accountId).available() : BigDecimal.ZERO);
        }
        Acct a = requireAcct(r.accountId);
        a.reserved = a.reserved.subtract(r.amount);
        r.state = ResState.RELEASED;
        return new ReleaseFundsResponse(reservationId, r.amount, a.available());
    }

    @Override
    public TransferFundsResponse transferFunds(String idempotencyKey, TransferFundsRequest req) {
        Acct from = requireAcct(req.fromAccountId());
        Acct to = requireAcct(req.toAccountId());
        from.balance = from.balance.subtract(req.debitAmount());
        to.balance = to.balance.add(req.creditAmount());
        consumedIdempotencyKeys.add(idempotencyKey);
        return new TransferFundsResponse(req.fromAccountId(), req.toAccountId(),
                req.debitAmount(), from.balance, to.balance);
    }

    @Override
    public CreditFundsResponse creditFunds(String idempotencyKey, CreditFundsRequest req) {
        // P0-1 fault: simulira banka-core 5xx blip POSLE F3 commit-a, PRE menjanja salda.
        if (failNextCreditCount.get() > 0
                && (failCreditOnlyForAccountId == null
                        || failCreditOnlyForAccountId.equals(req.accountId()))) {
            failNextCreditCount.decrementAndGet();
            throw new BankaCoreClientException(503, "credit privremeno nedostupan (forsiran fault)");
        }
        Acct a = requireAcct(req.accountId());
        a.balance = a.balance.add(req.amount());
        consumedIdempotencyKeys.add(idempotencyKey);
        return new CreditFundsResponse(req.accountId(), req.amount(), a.balance);
    }

    @Override
    public DebitFundsResponse debitFunds(String idempotencyKey, DebitFundsRequest req) {
        Acct a = requireAcct(req.accountId());
        if (a.available().compareTo(req.amount()) < 0) {
            throw new BankaCoreClientException(409, "insufficient");
        }
        a.balance = a.balance.subtract(req.amount());
        consumedIdempotencyKeys.add(idempotencyKey);
        return new DebitFundsResponse(req.accountId(), req.amount(), a.balance);
    }

    /**
     * <b>N4 (P0-T2):</b> autoritativan read-back banka-core dedup store-a — vraca da li je
     * kljuc VEC KONZUMIRAN (uspesnim mutirajucim pozivom ili rucnim {@link #markIdempotencyConsumed}).
     */
    @Override
    public boolean isIdempotencyKeyConsumed(String idempotencyKey) {
        return consumedIdempotencyKeys.contains(idempotencyKey);
    }

    @Override
    public InternalAccountDto getAccount(Long accountId) {
        return toDto(accountId, requireAcct(accountId));
    }

    @Override
    public InternalAccountDto getPreferredAccount(String userRole, Long userId, String currencyCode) {
        Long accountId = preferredByOwner.get(userRole + "|" + userId);
        if (accountId == null) {
            throw new BankaCoreClientException(404, "nema preferred racuna za " + userRole + "/" + userId);
        }
        return toDto(accountId, requireAcct(accountId));
    }

    @Override
    public InternalAccountDto getBankTradingAccount(String currencyCode) {
        Long accountId = bankTradingByCcy.get(currencyCode);
        if (accountId == null) {
            throw new BankaCoreClientException(404, "nema bankinog trading racuna u " + currencyCode);
        }
        return toDto(accountId, requireAcct(accountId));
    }

    /**
     * <b>P2-7:</b> exercise put forsira OTC access gate koji za klijenta trazi
     * {@code TRADE_STOCKS} autoritet (razresen preko {@link BankaCoreClient#getUserPermissions}
     * u {@code TradingJwtAuthenticationFilter}). Invarijant testovi tretiraju kupca kao
     * legitimnog OTC ucesnika (klijent sa pravom trgovine), pa fake vraca {@code TRADE_STOCKS}
     * za svaki email — bez ovoga svaki invarijant exercise bi dobio 403 na access gate-u.
     */
    @Override
    public List<String> getUserPermissions(String email) {
        return List.of("TRADE_STOCKS");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private InternalAccountDto toDto(Long id, Acct a) {
        Long ownerClientId = ownerClientIdFor(id);
        return new InternalAccountDto(id, "ACC-" + id, "Owner " + id,
                a.balance, a.available(), a.reserved, a.currency, "ACTIVE",
                ownerClientId, null, "CHECKING");
    }

    /**
     * Vraca CLIENT ownerClientId za racun ako je mapiran preko CLIENT preferred
     * mapiranja — orchestrator {@code verifyAccountOwnership} ga proverava za
     * CLIENT racune kad je buyerAccountId eksplicitno prosledjen.
     */
    private Long ownerClientIdFor(Long accountId) {
        for (Map.Entry<String, Long> e : preferredByOwner.entrySet()) {
            if (e.getValue().equals(accountId) && e.getKey().startsWith("CLIENT|")) {
                return Long.valueOf(e.getKey().substring("CLIENT|".length()));
            }
        }
        return null;
    }

    private Acct requireAcct(Long accountId) {
        Acct a = accounts.get(accountId);
        if (a == null) {
            throw new BankaCoreClientException(404, "racun ne postoji: " + accountId);
        }
        return a;
    }

    private Reservation requireReservation(String reservationId) {
        Reservation r = reservations.get(reservationId);
        if (r == null) {
            throw new BankaCoreClientException(404, "rezervacija ne postoji: " + reservationId);
        }
        return r;
    }
}
