package rs.raf.banka2_bek.employee.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.banka2_bek.employee.model.Employee;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    boolean existsByEmail(String email);

        boolean existsByEmailAndIdNot(String email, Long id);

    boolean existsByUsername(String username);

    Optional<Employee> findByEmail(String email);

    /**
     * P1-auth-2 (R2 1365): case-insensitive lookup za login/lockout putanju
     * (vidi {@code UserRepository.findByEmailIgnoreCase}).
     */
    Optional<Employee> findByEmailIgnoreCase(String email);

    /**
     * R1-618: locking varijanta za mutirajuce lockout putanje — vidi
     * {@code UserRepository.findByEmailIgnoreCaseForUpdate}. PESSIMISTIC_WRITE
     * serijalizuje read-modify-write na {@code failedLoginAttempts} brojacu.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Employee e WHERE LOWER(e.email) = LOWER(:email)")
    Optional<Employee> findByEmailIgnoreCaseForUpdate(@Param("email") String email);

    // R1-620: jedinstven JPQL fragment za oba findByFilters preopterecenja
    // (paginiran + ne-paginiran) — ranije ista WHERE klauza copy-paste-ovana 2x.
    // PostgreSQL ne moze da zakljuci tip kad je `:param` null, zato eksplicitan cast
    // (vidi CLAUDE.md Runda 24.04 PG migracija — ista popravka za PaymentRepository/TransactionRepository).
    String FILTER_QUERY = "SELECT e FROM Employee e WHERE " +
            "(cast(:email as string) IS NULL OR LOWER(e.email) LIKE LOWER(CONCAT('%', cast(:email as string), '%'))) AND " +
            "(cast(:firstName as string) IS NULL OR LOWER(e.firstName) LIKE LOWER(CONCAT('%', cast(:firstName as string), '%'))) AND " +
            "(cast(:lastName as string) IS NULL OR LOWER(e.lastName) LIKE LOWER(CONCAT('%', cast(:lastName as string), '%'))) AND " +
            "(cast(:position as string) IS NULL OR LOWER(e.position) LIKE LOWER(CONCAT('%', cast(:position as string), '%')))";

    @Query(FILTER_QUERY)
    Page<Employee> findByFilters(
            @Param("email") String email,
            @Param("firstName") String firstName,
            @Param("lastName") String lastName,
            @Param("position") String position,
            Pageable pageable);

    // Ne-paginirana varijanta za interni API (faza2c-A) — actuary domen filtrira
    // zaposlene po atributima posle ekstrakcije. Deli FILTER_QUERY sa paginiranom varijantom.
    @Query(FILTER_QUERY)
    List<Employee> findByFilters(
            @Param("email") String email,
            @Param("firstName") String firstName,
            @Param("lastName") String lastName,
            @Param("position") String position);

    /**
     * OT-1061: id-evi svih AKTIVNIH zaposlenih sa datom permisijom (npr.
     * {@code "SUPERVISOR"}). Koristi ga interni {@code /internal/users/supervisors}
     * endpoint da trading-service razresi primaoce tax-FX-failure notifikacije —
     * trading nema listu supervizora, pa je rezolvuje preko banka-core seam-a.
     * {@code DISTINCT} jer je {@code permissions} {@code @ElementCollection} (join).
     */
    @Query("SELECT DISTINCT e.id FROM Employee e JOIN e.permissions p "
            + "WHERE p = :permission AND e.active = true ORDER BY e.id")
    List<Long> findActiveEmployeeIdsByPermission(@Param("permission") String permission);
}
