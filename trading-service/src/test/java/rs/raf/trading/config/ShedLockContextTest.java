package rs.raf.trading.config;

import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N3 FIX (concurrency): verifikuje da se ShedLock infrastruktura podigne kad su
 * scheduleri ukljuceni ({@code trading.scheduling.enabled=true}) — LockProvider
 * bean postoji i {@code shedlock} tabela je kreirana (Hibernate ddl-auto preko
 * {@link ShedLockEntity}).
 *
 * <p>Ostali testovi gase scheduling (deterministican kontekst), pa
 * ShedLockConfig tamo ne ucitava; ovaj test ga eksplicitno ukljuci da dokaze da
 * produkciona konfiguracija (sa schedulerima) ispravno startuje.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "trading.scheduling.enabled=true")
class ShedLockContextTest {

    @Autowired
    private LockProvider lockProvider;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("N3: LockProvider bean postoji i shedlock tabela je kreirana kad je scheduling ON")
    void lockProviderBeanAndShedlockTableExist() throws Exception {
        assertThat(lockProvider).isNotNull();

        // shedlock tabela mora postojati (ddl-auto je kreira iz ShedLockEntity).
        try (var conn = dataSource.getConnection()) {
            var meta = conn.getMetaData();
            boolean found = false;
            try (var rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    if ("shedlock".equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("shedlock tabela mora biti kreirana").isTrue();
        }
    }
}
