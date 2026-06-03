package rs.raf.banka2_bek.account.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountCategory;
import rs.raf.banka2_bek.account.model.AccountStatus;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByClientIdAndStatusOrderByAvailableBalanceDesc(Long clientId, AccountStatus status);

    Optional<Account> findFirstByAccountCategoryAndCurrency_Code(AccountCategory accountCategory, String currencyCode);

    /**
     * P0-B3: PESSIMISTIC_WRITE varijanta {@link #findFirstByAccountCategoryAndCurrency_Code}.
     * Koristi se kad se balance bankinog racuna (npr. BANK_TRADING — prima provizije)
     * citanje-modifikacija-pisanje azurira: bez locka konkurentni settlement-i (commit/
     * debit/transfer provizija) gube update (lost-update) jer Account nema {@code @Version}.
     * Vraca prvi racun date kategorije i valute, zakljucan do kraja transakcije.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountCategory = :category AND a.currency.code = :currencyCode "
            + "ORDER BY a.id ASC")
    List<Account> findByAccountCategoryAndCurrencyCodeForUpdate(@Param("category") AccountCategory category,
                                                                @Param("currencyCode") String currencyCode);

    List<Account> findByClientId(Long clientId);

    // R1-307: svi racuni jedne kompanije (svih statusa) — za proveru duplikata naziva
    // poslovnih racuna pri rename-u, nezavisno od statusa i od toga ko renamuje.
    List<Account> findByCompanyId(Long companyId);

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountNumber = :accountNumber")
    Optional<Account> findForUpdateByAccountNumber(@Param("accountNumber") String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findForUpdateById(@Param("id") Long id);

    @Query("SELECT a FROM Account a WHERE a.company.registrationNumber = :regNumber AND a.currency.code = :currencyCode AND a.status = 'ACTIVE'")
    Optional<Account> findBankAccountByCurrency(@Param("regNumber") String regNumber, @Param("currencyCode") String currencyCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.company.registrationNumber = :regNumber AND a.currency.code = :currencyCode AND a.status = 'ACTIVE'")
    Optional<Account> findBankAccountForUpdateByCurrency(@Param("regNumber") String regNumber, @Param("currencyCode") String currencyCode);

    @Query("""
            SELECT DISTINCT a FROM Account a
            LEFT JOIN a.client c
            LEFT JOIN a.company co
            LEFT JOIN co.authorizedPersons ap
            WHERE (c.id = :clientId OR ap.client.id = :clientId)
              AND a.status = :status
            ORDER BY a.availableBalance DESC
            """)
    List<Account> findAccessibleAccounts(@Param("clientId") Long clientId,
                                         @Param("status") AccountStatus status);

    // PG cast za null-safe String parametar (vidi CLAUDE.md Runda 24.04).
    @Query("SELECT a FROM Account a LEFT JOIN a.client c LEFT JOIN a.company co WHERE "
            + "(cast(:ownerName as string) IS NULL OR "
            + "LOWER(CONCAT(c.firstName, ' ', c.lastName)) LIKE LOWER(CONCAT('%', cast(:ownerName as string), '%')) OR "
            + "LOWER(co.name) LIKE LOWER(CONCAT('%', cast(:ownerName as string), '%')))")
    Page<Account> findAllWithOwnerFilter(@Param("ownerName") String ownerName, Pageable pageable);

    // Sc 49 fix (T8-003): vraca SAMO BANK_TRADING racune banke, jer InvestmentFundService.ensureAccountCanBeUsed
    // odbija sve ostale kategorije. FE supervizor flow ("Uplati u ime banke") prikazuje rezultat ove rute u
    // FundInvestDialog dropdown-u — bez filtera korisnik vidi sve bankine racune ukljucujuci fund-specific
    // i FX racune koje BE potom odbija sa AccessDeniedException.
    @Query("SELECT a FROM Account a WHERE a.company.registrationNumber = :regNumber "
            + "AND a.accountCategory = rs.raf.banka2_bek.account.model.AccountCategory.BANK_TRADING")
    List<Account> findBankAccounts(@Param("regNumber") String regNumber);

    @Query("SELECT a FROM Account a WHERE a.company.registrationNumber = :regNumber AND a.currency.id = :currencyId AND a.status = 'ACTIVE' ORDER BY a.id ASC")
    java.util.List<Account> findBankAccountsByCurrencyId(@Param("regNumber") String regNumber, @Param("currencyId") Long currencyId);

    default Optional<Account> findBankAccountByCurrencyId(String regNumber, Long currencyId) {
        return findBankAccountsByCurrencyId(regNumber, currencyId).stream().findFirst();
    }

    @Modifying
    @Query("UPDATE Account a SET a.dailySpending = 0")
    int resetDailySpending();

    @Modifying
    @Query("UPDATE Account a SET a.monthlySpending = 0")
    int resetMonthlySpending();

    /**
     * P1-4 fix: atomic increment of daily + monthly spending for a single account.
     *
     * <p>Koristi se kad medjubankarsko placanje stigne do COMMITTED stanja
     * ({@code InterbankPaymentAsyncService.executeAsync}) — same-bank flow vec
     * inkrementira spending u-mestu kroz dirty-checked entitet, ali interbank
     * async flow tece pod {@code Propagation.NEVER} (van transakcije), pa nema
     * dirty-check-a. Atomic UPDATE je i racetolerantan (citanje-modifikacija-pisanje
     * u jednom SQL-u) i transakcioni (SimpleJpaRepository {@code @Modifying} metode
     * idu u sopstvenoj REQUIRED transakciji). Vraca broj azuriranih redova (0 ako
     * racun ne postoji).
     */
    @Modifying
    @Query("UPDATE Account a SET a.dailySpending = a.dailySpending + :amount, "
            + "a.monthlySpending = a.monthlySpending + :amount WHERE a.accountNumber = :accountNumber")
    int incrementSpending(@Param("accountNumber") String accountNumber,
                          @Param("amount") java.math.BigDecimal amount);
}
