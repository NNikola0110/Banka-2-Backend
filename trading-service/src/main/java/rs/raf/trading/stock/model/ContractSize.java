package rs.raf.trading.stock.model;

/**
 * OT-1048 — jedinstveni izvor istine za podrazumevani {@code Contract Size}
 * po tipu hartije (spec Celina 3 §136/§162/§207).
 *
 * <p>Spec definise standardne velicine ugovora:
 * <ul>
 *   <li><b>STOCK</b> — Contract Size = 1 (§136).</li>
 *   <li><b>FOREX</b> — Contract Size = standardno 1000 (§162, "moze zavisiti od
 *       berze"). Sve FOREX hartije u {@code trading-seed.sql} su seed-ovane sa
 *       {@code contract_size = 1000}; ova konstanta je autoritativan default kad
 *       (defanzivno) {@code listing.contractSize} ipak dodje {@code null}.</li>
 *   <li><b>FUTURES</b> — Contract Size dolazi sa berze/API-ja po hartiji (npr.
 *       CME Crude Oil = 1000, Gold = 100), pa nema univerzalni default; fallback
 *       je {@code 1} (neutralno) ako vrednost izostane.</li>
 * </ul>
 *
 * <p><b>OT-1048 [BUG-FOUND fix]:</b> ranije su {@code ListingMapper},
 * {@code OrderMapper}, {@code OrderServiceImpl} i {@code TaxRealizedGainCalculator}
 * SVI default-ovali {@code null} contractSize na {@code 1} — sto je za FOREX
 * pogresno (spec trazi 1000) i mis-pricuje rezervaciju/porez za faktor 1000 ako
 * FOREX listing ikad dodje bez contractSize-a. Sada se default razresava ovde,
 * po tipu hartije, KONZISTENTNO svuda gde se novac (rezervacija/porez) i prikaz
 * (maintenance/initial margin) racunaju — pa display nikad ne divergira od
 * order-engine pricinga.
 *
 * <p><b>Invarijanta:</b> proizvodne FOREX hartije UVEK nose {@code contract_size
 * = 1000} (seed). Ovaj default je defanzivni safety-net (kolona je nullable, bez
 * DB default-a), ne aktivni proizvodni put.
 */
public final class ContractSize {

    /** STOCK Contract Size = 1 (spec §136). */
    public static final int STOCK_DEFAULT = 1;

    /** FOREX Contract Size = standardno 1000 (spec §162). */
    public static final int FOREX_DEFAULT = 1000;

    /** Neutralni fallback kad tip nije FOREX/STOCK ili je nepoznat (FUTURES dolazi sa API-ja). */
    public static final int NEUTRAL_DEFAULT = 1;

    private ContractSize() {}

    /**
     * Razresava efektivni contract size: ako je {@code explicit} (sa hartije/order-a)
     * prisutan i pozitivan, koristi se on; inace se vraca spec-default po
     * {@code listingType} (FOREX → 1000, STOCK → 1, ostalo → 1).
     */
    public static int resolve(Integer explicit, ListingType listingType) {
        if (explicit != null && explicit > 0) {
            return explicit;
        }
        return defaultFor(listingType);
    }

    /**
     * Podrazumevani contract size po tipu hartije kad eksplicitna vrednost izostane.
     */
    public static int defaultFor(ListingType listingType) {
        if (listingType == ListingType.FOREX) {
            return FOREX_DEFAULT;
        }
        if (listingType == ListingType.STOCK) {
            return STOCK_DEFAULT;
        }
        return NEUTRAL_DEFAULT;
    }
}
