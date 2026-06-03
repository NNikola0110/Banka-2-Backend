package rs.raf.trading.common;

import java.math.BigDecimal;

/**
 * R1-718 — JEDINSTVEN izvor istine za provizijske stope i cap-ove naloga.
 *
 * <p>Spec (Celina 3): MARKET/STOP nalog placa {@code min(14% * cena, $7)},
 * LIMIT/STOP_LIMIT nalog {@code min(24% * cena, $12)} ("koji iznos je manji").
 *
 * <p>Pre konsolidacije su iste vrednosti bile DUPLIRANE nezavisno na 2 mesta:
 * {@code SingleOrderExecutor} (imenovane konstante {@code MARKET_COMMISSION_RATE}
 * itd.) i {@code OrderServiceImpl.calculateCommissionInListingCurrency}
 * (inline {@code new BigDecimal("0.14")} / {@code "7"} / {@code "0.24"} / {@code "12"}).
 * Promena tarife (npr. usaglasavanje sa novim spec-om) zahtevala bi izmenu na
 * oba mesta; lako je da jedna promakne pa da fill-engine i create-flow racunaju
 * razlicitu proviziju. Ovde su vrednosti definisane JEDNOM.</p>
 *
 * <p><b>Vrednosti su nepromenjene</b> — cista konsolidacija bez promene
 * ponasanja (svaka putanja zadrzava svoju formulu primene, menja se samo izvor
 * literala). USD cap od $7/$12 ima smisla za USD-denominovane listinge; za
 * non-USD listinge se tretira kao literal iznos u listing valuti (pragmaticna
 * aproksimacija jer se vecina hartija denominuje u USD).</p>
 */
public final class OrderCommissionPolicy {

    /** MARKET / STOP provizija: {@code min(14% * cena, $7)}. */
    public static final BigDecimal MARKET_COMMISSION_RATE = new BigDecimal("0.14");
    public static final BigDecimal MARKET_COMMISSION_CAP = new BigDecimal("7");

    /** LIMIT / STOP_LIMIT provizija: {@code min(24% * cena, $12)}. */
    public static final BigDecimal LIMIT_COMMISSION_RATE = new BigDecimal("0.24");
    public static final BigDecimal LIMIT_COMMISSION_CAP = new BigDecimal("12");

    private OrderCommissionPolicy() {
        // util konstanta — nema instanci
    }
}
