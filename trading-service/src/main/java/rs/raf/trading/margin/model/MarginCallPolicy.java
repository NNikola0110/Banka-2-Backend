package rs.raf.trading.margin.model;

import java.math.BigDecimal;

/**
 * R1 769: centralizovana margin-call politika blokiranja/odblokiranja racuna.
 *
 * <p>Marzni_Racuni.txt §133-139: racun se BLOKIRA kada raspoloziva pocetna marza
 * ({@code initialMargin − reservedMargin}) padne ISPOD margine odrzavanja (MM), a
 * ODBLOKIRA kada raspoloziva marza ponovo dostigne ili predje MM. Ranije je ovaj
 * isti prag bio inline-ovan na vise mesta ({@link MarginAccount} blok scheduler,
 * post-fill margin call u {@code MarginOrderSettlementService}, odblok pri uplati u
 * {@code MarginAccountService.deposit}) sa suptilno razlicitim izrazima (negde sirov
 * {@code initialMargin}, negde {@code availableInitialMargin}). Ovde je jedinstven
 * izvor istine: prag je UVEK raspoloziva marza, konzistentno sa
 * {@code MarginAccountRepository.findEligibleForBlock}.
 */
public final class MarginCallPolicy {

    private MarginCallPolicy() {
    }

    /**
     * @return {@code true} ako raspoloziva pocetna marza padne ispod margine
     *         odrzavanja (racun bi trebalo blokirati).
     */
    public static boolean shouldBlock(MarginAccount account) {
        return account.getAvailableInitialMargin()
                .compareTo(account.getMaintenanceMargin()) < 0;
    }

    /**
     * @return {@code true} ako raspoloziva pocetna marza dostigne ili predje
     *         marginu odrzavanja (racun bi trebalo odblokirati).
     */
    public static boolean shouldUnblock(MarginAccount account) {
        return account.getAvailableInitialMargin()
                .compareTo(account.getMaintenanceMargin()) >= 0;
    }

    /** Deficit raspolozive marze u odnosu na MM (>= 0 kad treba blokirati). */
    public static BigDecimal deficit(MarginAccount account) {
        return account.getMaintenanceMargin().subtract(account.getAvailableInitialMargin());
    }
}
