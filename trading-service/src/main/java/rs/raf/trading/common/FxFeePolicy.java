package rs.raf.trading.common;

import java.math.BigDecimal;

/**
 * P2-profit-fx-fee-1 (R5 1877) — JEDINSTVEN izvor istine za menjacnicku
 * (FX) marzu/proviziju koju trading-service naplacuje KLIJENTU kada se
 * novcana noga konvertuje izmedju valuta.
 *
 * <p>Pre konsolidacije je ista 1% konstanta bila DUPLIRANA nezavisno na 4
 * mesta ({@code CurrencyConversionService.FX_MARGIN},
 * {@code SingleOrderExecutor.SELL_FX_MARGIN},
 * {@code InvestmentFundService.FX_FEE_RATE},
 * {@code FundLiquidationService.FX_FEE_RATE}) — svaka kao zaseban
 * {@code new BigDecimal("0.01")}. Promena politike (npr. usaglasavanje sa
 * Celina 2 menjacnicom) bi zahtevala 4 odvojene izmene; lako je da jedna
 * promakne pa da fee divergira po putanji. Ovde je rate definisan JEDNOM.</p>
 *
 * <p><b>Vrednost je nepromenjena (0.01 = 1%)</b> — ovo je CISTA konsolidacija
 * bez promene ponasanja. Efektivna naknada na svakoj od 4 putanje ostaje
 * byte-identicna onome sto je bila pre (svaka putanja zadrzava SVOJU formulu
 * primene — multiplikativno na kurs u {@code CurrencyConversionService},
 * ravno na iznos na ostale 3 — menja se samo izvor literala 0.01).</p>
 *
 * <p>Zaposleni (bankini racuni) i iste valute uvek imaju 0 fee — to je odluka
 * pozivaoca (gate na {@code chargeFx}/{@code isClient}/{@code multiCurrency}),
 * ne ove politike.</p>
 */
public final class FxFeePolicy {

    /**
     * Menjacnicka marza/provizija (1%) koja se naplacuje klijentu na FX
     * konverziju novcane noge. Konzervativan match za Celina 2 menjacnicu
     * (+2% spread + 0.5% komisija ~2.5%), kombinovan u jedan citljiv fee.
     */
    public static final BigDecimal FX_FEE_RATE = new BigDecimal("0.01");

    private FxFeePolicy() {
        // util konstanta — nema instanci
    }
}
