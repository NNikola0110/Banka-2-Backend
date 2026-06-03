package rs.raf.trading.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * N3 FIX (concurrency): JPA mapiranje ShedLock-ove {@code shedlock} tabele.
 *
 * <p>ShedLock {@code JdbcTemplateLockProvider} ocekuje tabelu sa kolonama
 * {@code name} (PK), {@code lock_until}, {@code locked_at}, {@code locked_by}
 * (ShedLock dokumentacija). Posto trading-service koristi Hibernate
 * {@code ddl-auto} (nema Liquibase/Flyway), tabelu kreiramo kao JPA entitet da je
 * Hibernate napravi automatski na startup-u — bez rucnog DDL-a ili migracionog
 * alata. Entitet se NE cita/pise iz aplikacionog koda (ShedLock je vlasnik preko
 * JdbcTemplate-a); postoji iskljucivo da bi {@code ddl-auto} kreirao semu.
 *
 * <p>PostgreSQL: {@code lock_until}/{@code locked_at} su {@code TIMESTAMP}.
 * {@link Instant} + {@code timestamp} kolona daje UTC semantiku koju ShedLock
 * koristi (poredjenje {@code lock_until > now()}).
 */
@Entity
@Table(name = "shedlock")
public class ShedLockEntity {

    @Id
    @Column(name = "name", length = 64, nullable = false)
    private String name;

    @Column(name = "lock_until", nullable = false)
    private Instant lockUntil;

    @Column(name = "locked_at", nullable = false)
    private Instant lockedAt;

    @Column(name = "locked_by", length = 255, nullable = false)
    private String lockedBy;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getLockUntil() {
        return lockUntil;
    }

    public void setLockUntil(Instant lockUntil) {
        this.lockUntil = lockUntil;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Instant lockedAt) {
        this.lockedAt = lockedAt;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }
}
