package rs.raf.trading.actuary.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.security.TradingUserResolver;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integracioni test schedulera — pun Spring kontekst (H2 test profil), realan
 * {@code ActuaryLimitResetScheduler} + realan {@code ActuaryInfoRepository}.
 *
 * NAPOMENA (copy-first ekstrakcija, faza 2c): monolitni test je seedovao
 * {@code Employee} entitete. U trading-service-u {@code Employee} ne postoji
 * lokalno — {@code ActuaryInfo} se seeduje direktno sa soft {@code employeeId}.
 * {@code @Scheduled} je inertan (TradingServiceApplication nema @EnableScheduling),
 * pa se {@code resetDailyLimits()} poziva eksplicitno iz testa.
 */
@SpringBootTest
@ActiveProfiles("test")
class ActuaryLimitResetSchedulerIntegrationTest {

    @Autowired
    private ActuaryLimitResetScheduler scheduler;

    @Autowired
    private ActuaryInfoRepository actuaryInfoRepository;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

    @MockitoBean
    private TradingUserResolver userResolver;

    @BeforeEach
    void clean() {
        actuaryInfoRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        actuaryInfoRepository.deleteAll();
    }

    private ActuaryInfo savedActuaryInfo(Long employeeId, BigDecimal usedLimit) {
        ActuaryInfo info = new ActuaryInfo();
        info.setEmployeeId(employeeId);
        info.setActuaryType(ActuaryType.AGENT);
        info.setDailyLimit(BigDecimal.valueOf(10000));
        info.setUsedLimit(usedLimit);
        info.setNeedApproval(false);
        return actuaryInfoRepository.save(info);
    }

    @Test
    @DisplayName("resets usedLimit to 0 for all actuaries")
    void resetsUsedLimitToZero() {
        savedActuaryInfo(2001L, BigDecimal.valueOf(5000));
        savedActuaryInfo(2002L, BigDecimal.valueOf(8000));

        scheduler.resetDailyLimits();

        List<ActuaryInfo> all = actuaryInfoRepository.findAll();
        assertThat(all).hasSize(2);
        for (ActuaryInfo info : all) {
            assertThat(info.getUsedLimit()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    @DisplayName("runs without error when no actuaries exist")
    void noActuaries() {
        scheduler.resetDailyLimits();

        assertThat(actuaryInfoRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("does not change dailyLimit, only usedLimit")
    void doesNotChangeDailyLimit() {
        ActuaryInfo info = savedActuaryInfo(2003L, BigDecimal.valueOf(3000));
        BigDecimal originalDailyLimit = info.getDailyLimit();

        scheduler.resetDailyLimits();

        ActuaryInfo updated = actuaryInfoRepository.findById(info.getId()).orElseThrow();
        assertThat(updated.getDailyLimit()).isEqualByComparingTo(originalDailyLimit);
        assertThat(updated.getUsedLimit()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
