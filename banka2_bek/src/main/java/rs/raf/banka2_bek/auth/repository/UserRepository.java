package rs.raf.banka2_bek.auth.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.banka2_bek.auth.model.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /**
     * P1-auth-2 (R2 1365): case-insensitive lookup za login/lockout putanju.
     * {@link AccountLockoutService} normalizuje email na lowercase, a login je
     * koristio exact {@code findByEmail} — razlicit case je razilazio login
     * lookup od lockout brojaca (lockout pratio jedan kljuc, login drugi).
     */
    Optional<User> findByEmailIgnoreCase(String email);

    /**
     * R1-618: locking varijanta za mutirajuce lockout putanje
     * ({@code recordFailure}/{@code recordSuccess}). PESSIMISTIC_WRITE serijalizuje
     * read-modify-write na {@code failedLoginAttempts}, pa dva paralelna neuspela
     * login-a ne mogu da procitaju istu staru vrednost i oba inkrementiraju na N+1
     * (lost update brojaca → napadac dobija dodatne pokusaje pre lockout-a).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE LOWER(u.email) = LOWER(:email)")
    Optional<User> findByEmailIgnoreCaseForUpdate(@Param("email") String email);

    boolean existsByEmail(String email);
}