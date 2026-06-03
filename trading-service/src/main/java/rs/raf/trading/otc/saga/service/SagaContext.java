package rs.raf.trading.otc.saga.service;

import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.otc.model.OtcContract;

/**
 * Mutabilni nosac stanja jedne SAGA instance kroz faze F1–F5 i kompenzatore.
 * Faze ga popunjavaju (F1 razresava racune + rezervaciju), naredne faze i
 * kompenzatori ga citaju (F3/C3 koriste {@code reservationId} +
 * {@code reservedBuyerAmount}). Nije perzistentan — perzistira se {@link
 * rs.raf.trading.otc.saga.model.SagaLog}; ovo je in-memory carrier u okviru
 * jednog {@code exercise(...)} poziva.
 */
class SagaContext {
    final OtcContract contract;
    final Long requestedBuyerAccountId;
    String listingCurrency;
    InternalAccountDto buyerAccount;
    InternalAccountDto sellerAccount;
    String reservationId;
    java.math.BigDecimal reservedBuyerAmount;
    /** Iznos koji je F3 kreditirao prodavcu (u valuti prodavca) — C3 ga vraca reverznim transferom. */
    java.math.BigDecimal creditedToSeller;

    /**
     * <b>BUG-W2-01:</b> da li je F1 OVE saga-e zaista kreirao novu rezervaciju
     * (reserve-at-exercise put). Ako je F1 REUSE-ovao accept-time rezervaciju,
     * ostaje {@code false} pa C1 NE oslobadja tudju rezervaciju (ugovor ostaje
     * pokriven dok ne istekne / ne bude iskoriscen).
     */
    boolean buyerReservationCreatedHere;
    /**
     * <b>BUG-W2-01:</b> da li je F2 OVE saga-e zaista rezervisala akcije prodavca
     * (reserve-at-exercise put). Ako je F2 bio no-op (accept-time pokrice),
     * ostaje {@code false} pa C2 NE dekrementuje accept-time {@code reservedQuantity}.
     */
    boolean sellerSharesReservedHere;
    /** Koliko je akcija F2 OVE saga-e rezervisala ({@code need}) — C2 oslobadja tacno toliko. */
    int sellerSharesReservedAmount;

    /**
     * <b>P0-1 (Bug A):</b> da li je F3 {@code commitFunds} obavljen (kupac debitovan,
     * rezervacija zatvorena). C3 granu bira na osnovu (commit, credit) para.
     */
    boolean f3CommitDone;
    /**
     * <b>N4 (P0-T2):</b> write-ahead namera da F3 {@code creditFunds} krece (perzistovano PRE
     * poziva). C3 koristi {@code (commitDone, creditIntent, creditDone)} trojku: kad je
     * {@code commitDone && creditIntent && !creditDone}, credit je MOZDA izvrsen (crash u prozoru)
     * → pun reverzni transfer (ne commit-only refund), pa se ne stvara novac.
     */
    boolean f3CreditIntent;
    /** <b>P0-1 (Bug A):</b> da li je F3 {@code creditFunds} (isplata prodavcu) obavljen. */
    boolean f3CreditDone;
    /**
     * <b>N4 (P0-T2):</b> RECOVERY-only signal: postavlja se SAMO u {@code rebuildContext} i to
     * AUTORITATIVNO — kad je {@code f3CommitDone && f3CreditIntent && !f3CreditDone} (crash u prozoru
     * persist(intent)↔persist(done)), recovery pita banka-core da li je F3-credit idempotency kljuc
     * VEC KONZUMIRAN. {@code true} → credit je STVARNO izvrsen (prodavac kreditiran) → C3 pun reverzni
     * transfer (tacno ponisteno, I1). {@code false} → credit NIJE izvrsen → C3 commit-only refund kupcu
     * (prodavac NETAKNUT — nikad nije primio novac, ne sme se debitovati; bez ovoga saga STUCK ili I1 puca).
     * U LIVE putu se NIKAD ne postavlja (tamo je creditFunds bacio → credit nije izvrsen → C3 commit-only).
     */
    boolean f3CreditMaybeApplied;
    /**
     * <b>P0-1 (Bug B):</b> da li je F4 dekrement seller pozicije komitovan PRE buyer
     * kreditiranja. C4 je partial-safe: vraca seller akcije SAMO ako je ovo true, i
     * dira buyer poziciju samo kad je seller-leg primenjen.
     */
    boolean f4SellerApplied;
    /**
     * <b>N1 (P0-T2):</b> da li je ovo RECOVERY (real-crash) kontekst (rekonstruisan u
     * {@code rebuildContext}) nasuprot LIVE in-process compensate (ista otvorena outer tx).
     * C4/C5 granaju po ovome: u LIVE-u F4/F5 lokalni efekti su u OTVORENOJ outer tx-i pa se
     * restore-uju (in-memory flag-ovi); u RECOVERY-ju su (zbog all-or-nothing outer tx-a)
     * ili commit-ovani (→ COMPLETED, C4/C5 se ne dosegnu) ili rollback-ovani (→ ne restore-uj),
     * sto se cita iz OUTER-TX markera {@code f4Committed}/{@code f5Committed} (ne iz qty-snapshot-a).
     */
    boolean recovery;

    SagaContext(OtcContract c, Long buyerAccountId) {
        this.contract = c;
        this.requestedBuyerAccountId = buyerAccountId;
    }
}
