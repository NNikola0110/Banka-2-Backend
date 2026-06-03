package rs.raf.trading.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * N3 FIX (concurrency): aktivira ShedLock distribuirani lock nad {@code @Scheduled}
 * poslovima trading-service-a.
 *
 * <p><b>Zasto:</b> order-engine (10s/30s), DCA (60s) i drugi {@code @Scheduled}
 * ciklusi se na VISE k8s replika izvrsavaju paralelno — bez locka 2 replike
 * istovremeno fill-uju iste APPROVED ordere / kreiraju iste dospele DCA naloge →
 * double-fill / dupli buy / double-charge. {@code @SchedulerLock} na hot metodama
 * + {@code JdbcTemplateLockProvider} ({@code shedlock} tabela u trading_db)
 * obezbedjuju da SAMO jedna replika drzi lock po ciklusu; ostale preskoce.
 * {@code @Version} (na Order/RecurringOrder) je komplementaran in-process guard.
 *
 * <p>{@code defaultLockAtMostFor = PT30S}: ako replika koja drzi lock crashne pre
 * release-a, lock se sam oslobodi posle 30s (sledeci ciklus moze da preuzme) —
 * nema permanentnog "zaglavljenog" locka. Per-posao {@code lockAtMostFor} se moze
 * override-ovati na {@code @SchedulerLock} (npr. duzi za sporije poslove).
 *
 * <p>Gejtovano istim property-jem kao {@link SchedulingConfig}
 * ({@code trading.scheduling.enabled}, OFF u test profilu) — kad su scheduleri
 * ugaseni, ShedLock infrastruktura se ne podize (nema potrebe za LockProvider-om
 * dok nema {@code @Scheduled} okidanja).
 */
@Configuration
@ConditionalOnProperty(name = "trading.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
public class ShedLockConfig {

    /**
     * JDBC lock provider — cuva lock-ove u {@code shedlock} tabeli (kreira je
     * {@link ShedLockEntity} kroz Hibernate ddl-auto). {@code usingDbTime()} koristi
     * vreme baze (ne aplikacionog cvora) za poredjenje {@code lock_until} — robusno
     * na clock-skew izmedju k8s replika. {@code usingDbTime} i {@code withTimeZone}
     * se medjusobno iskljucuju u ShedLock-u (DB vreme je vec autoritativno), pa
     * NE postavljamo timeZone.
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .withTableName("shedlock")
                        .usingDbTime()
                        .build());
    }
}
