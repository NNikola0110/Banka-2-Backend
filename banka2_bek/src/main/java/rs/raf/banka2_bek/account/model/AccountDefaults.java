package rs.raf.banka2_bek.account.model;

import java.math.BigDecimal;

/**
 * R1-626: jedinstven izvor podrazumevanih vrednosti za novi racun.
 *
 * <p>Ranije su iste magic-vrednosti bile hardkodirane na 2 mesta
 * ({@code AccountServiceImplementation.createAccount} i
 * {@code AuthService.bootstrapClientArtifacts}) sa rizikom drift-a. Sada oba
 * puta citaju ove konstante.</p>
 *
 * <p>FE duplira iste podrazumevane limite u prikazu (250k / 1M) — vrednosti se
 * drze sinhronizovane rucno; eventualna {@code @ConfigurationProperties}
 * migracija (env-override) je veca promena van P3 cleanup scope-a.</p>
 */
public final class AccountDefaults {

    /** Podrazumevani dnevni limit placanja ako klijent ne prosledi vrednost. */
    public static final BigDecimal DEFAULT_DAILY_LIMIT = new BigDecimal("250000");

    /** Podrazumevani mesecni limit placanja ako klijent ne prosledi vrednost. */
    public static final BigDecimal DEFAULT_MONTHLY_LIMIT = new BigDecimal("1000000");

    /** Mesecna naknada za odrzavanje (RSD racuni); devizni racuni su oslobodjeni. */
    public static final BigDecimal MAINTENANCE_FEE = new BigDecimal("255");

    private AccountDefaults() {}
}
