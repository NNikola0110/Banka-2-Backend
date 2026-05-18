package rs.raf.trading.stock.util;

import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;

/**
 * Centralizovano razresavanje ISO koda valute za jedan listing.
 *
 * Prioritet:
 * <ol>
 *   <li>FOREX tip + baseCurrency → baseCurrency</li>
 *   <li>quoteCurrency ako je eksplicitno setovan (retko, koristi se u test data-ima)</li>
 *   <li>Mapiranje po exchange akronimu (NYSE/NASDAQ/CME → USD, LSE → GBP,
 *       XETRA → EUR, BELEX → RSD)</li>
 *   <li>Fallback USD</li>
 * </ol>
 *
 * Pre ekstrakcije, identicna logika se duplikovala u {@code OrderServiceImpl},
 * {@code TaxService} i {@code OtcService}. Promene zahteva (npr. dodavanje
 * nove berze) sad se rade na jednom mestu.
 */
public final class ListingCurrencyResolver {

    private ListingCurrencyResolver() {}

    /**
     * Vraca ISO kod valute za dati listing. Ne baca — pada na defaults.
     *
     * @param listing listing ciju valutu trazimo; moze biti {@code null}
     * @return ISO kod (npr. "USD", "EUR", "RSD")
     */
    public static String resolve(Listing listing) {
        if (listing == null) {
            return "USD";
        }
        if (listing.getListingType() == ListingType.FOREX
                && listing.getBaseCurrency() != null
                && !listing.getBaseCurrency().isBlank()) {
            return listing.getBaseCurrency();
        }
        if (listing.getQuoteCurrency() != null && !listing.getQuoteCurrency().isBlank()) {
            return listing.getQuoteCurrency();
        }
        String exchange = listing.getExchangeAcronym();
        if (exchange == null) {
            return "USD";
        }
        return switch (exchange.toUpperCase()) {
            case "NYSE", "NASDAQ", "CME" -> "USD";
            case "LSE" -> "GBP";
            case "XETRA" -> "EUR";
            case "BELEX" -> "RSD";
            default -> "USD";
        };
    }

    /**
     * Varijanta sa defensive try/catch — vraca fallback ako neki getter
     * baci (moguce u pojedinim mock-scenario testovima gde entiteti nisu
     * potpuno hidrirani).
     *
     * @param listing listing; moze biti null
     * @param fallback sta vratiti ako bilo sta pukne
     */
    public static String resolveSafe(Listing listing, String fallback) {
        if (listing == null) return fallback;
        try {
            return resolve(listing);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
