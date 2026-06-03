package rs.raf.trading.actuary.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.actuary.dto.ActuaryInfoDto;
import rs.raf.trading.actuary.dto.UpdateActuaryLimitDto;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.security.TradingUserResolver;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Integracioni test — pun Spring kontekst (H2 test profil), realan
 * {@code ActuaryService} + realan {@code ActuaryInfoRepository} (JPA persistencija
 * soft {@code employeeId} kolone).
 *
 * NAPOMENA (copy-first ekstrakcija, faza 2c): monolitni test je seedovao
 * {@code Employee}/{@code User} entitete (banka-core domen) i koristio
 * {@code IntegrationTestCleanup}. U trading-service-u {@code Employee} ne postoji
 * lokalno — {@code BankaCoreClient} i {@code TradingUserResolver} su mockovani
 * ({@code @MockitoBean}), a {@code ActuaryInfo} se seeduje direktno preko
 * repozitorijuma sa soft {@code employeeId}-evima.
 */
@SpringBootTest
@ActiveProfiles("test")
class ActuaryServiceImplIntegrationTest {

    private static final Long AGENT_MARKO_ID = 1001L;
    private static final Long AGENT_JELENA_ID = 1002L;
    private static final Long SUPERVISOR_NINA_ID = 1003L;

    @Autowired
    private ActuaryService actuaryService;

    @Autowired
    private ActuaryInfoRepository actuaryInfoRepository;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

    @MockitoBean
    private TradingUserResolver userResolver;

    @BeforeEach
    void setUp() {
        actuaryInfoRepository.deleteAll();

        actuaryInfoRepository.save(createActuaryInfo(AGENT_MARKO_ID, ActuaryType.AGENT,
                new BigDecimal("100000.00"), new BigDecimal("15000.00"), false));
        actuaryInfoRepository.save(createActuaryInfo(AGENT_JELENA_ID, ActuaryType.AGENT,
                new BigDecimal("50000.00"), new BigDecimal("999.99"), true));
        actuaryInfoRepository.save(createActuaryInfo(SUPERVISOR_NINA_ID, ActuaryType.SUPERVISOR,
                null, null, false));

        // BankaCoreClient.getUserById se zove samo za popunjavanje DTO-a (ime/email).
        lenient().when(bankaCoreClient.getUserById(anyString(), anyLong()))
                .thenAnswer(inv -> {
                    Long id = inv.getArgument(1);
                    return new InternalUserDto(id, "EMPLOYEE", "user" + id + "@banka.rs",
                            "Ime" + id, "Prezime" + id, true, "Agent");
                });
    }

    @AfterEach
    void tearDown() {
        actuaryInfoRepository.deleteAll();
    }

    private void authenticateAsSupervisor() {
        when(userResolver.resolveCurrent())
                .thenReturn(new UserContext(SUPERVISOR_NINA_ID, "EMPLOYEE"));
    }

    private ActuaryInfo createActuaryInfo(Long employeeId,
                                          ActuaryType type,
                                          BigDecimal dailyLimit,
                                          BigDecimal usedLimit,
                                          boolean needApproval) {
        ActuaryInfo info = new ActuaryInfo();
        info.setEmployeeId(employeeId);
        info.setActuaryType(type);
        info.setDailyLimit(dailyLimit);
        info.setUsedLimit(usedLimit);
        info.setNeedApproval(needApproval);
        return info;
    }

    @Test
    @DisplayName("resetAllUsedLimits resetuje samo agente, supervizor ostaje neizmenjen")
    void resetAllUsedLimitsResetsOnlyAgents() {
        actuaryService.resetAllUsedLimits();

        ActuaryInfo refreshedMarko = actuaryInfoRepository.findByEmployeeId(AGENT_MARKO_ID).orElseThrow();
        ActuaryInfo refreshedJelena = actuaryInfoRepository.findByEmployeeId(AGENT_JELENA_ID).orElseThrow();
        ActuaryInfo refreshedNina = actuaryInfoRepository.findByEmployeeId(SUPERVISOR_NINA_ID).orElseThrow();

        assertEquals(0, refreshedMarko.getUsedLimit().compareTo(BigDecimal.ZERO));
        assertEquals(0, refreshedJelena.getUsedLimit().compareTo(BigDecimal.ZERO));
        assertNull(refreshedNina.getUsedLimit());
        assertEquals(ActuaryType.SUPERVISOR, refreshedNina.getActuaryType());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    @DisplayName("R1-740: bulk resetAllUsedLimits (scheduler put) resetuje SAMO agente — supervizor usedLimit netaknut")
    void bulkResetAllUsedLimitsTouchesOnlyAgents() {
        // Supervizor sa NE-null usedLimit-om (ne realisticno spec-ski, ali dokazuje
        // da bulk JPQL ne dira SUPERVISOR red posle R1-740 WHERE actuaryType=AGENT).
        ActuaryInfo nina = actuaryInfoRepository.findByEmployeeId(SUPERVISOR_NINA_ID).orElseThrow();
        nina.setUsedLimit(new BigDecimal("777.00"));
        actuaryInfoRepository.saveAndFlush(nina);

        int updated = actuaryInfoRepository.resetAllUsedLimits();

        ActuaryInfo refreshedMarko = actuaryInfoRepository.findByEmployeeId(AGENT_MARKO_ID).orElseThrow();
        ActuaryInfo refreshedJelena = actuaryInfoRepository.findByEmployeeId(AGENT_JELENA_ID).orElseThrow();
        ActuaryInfo refreshedNina = actuaryInfoRepository.findByEmployeeId(SUPERVISOR_NINA_ID).orElseThrow();

        // Samo 2 agenta azurirana; supervizor netaknut (i dalje 777).
        assertEquals(2, updated);
        assertEquals(0, refreshedMarko.getUsedLimit().compareTo(BigDecimal.ZERO));
        assertEquals(0, refreshedJelena.getUsedLimit().compareTo(BigDecimal.ZERO));
        assertEquals(0, refreshedNina.getUsedLimit().compareTo(new BigDecimal("777.00")));
    }

    @Test
    @DisplayName("updateAgentLimit menja samo trazena polja i cuva ih u bazi")
    void updateAgentLimitPersistsChanges() {
        authenticateAsSupervisor();

        UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
        dto.setDailyLimit(new BigDecimal("250000.00"));
        dto.setNeedApproval(true);

        ActuaryInfoDto result = actuaryService.updateAgentLimit(AGENT_MARKO_ID, dto);

        // R4-1772: usedLimit/dailyLimit se sada persistuju u scale-4 (numeric(19,4)),
        // pa poredimo vrednosno (compareTo), ne striktno po scale-u.
        assertEquals(0, result.getDailyLimit().compareTo(new BigDecimal("250000.00")));
        assertTrue(result.isNeedApproval());
        assertEquals(0, result.getUsedLimit().compareTo(new BigDecimal("15000.00")));

        ActuaryInfo refreshed = actuaryInfoRepository.findByEmployeeId(AGENT_MARKO_ID).orElseThrow();
        assertEquals(0, refreshed.getDailyLimit().compareTo(new BigDecimal("250000.00")));
        assertTrue(refreshed.isNeedApproval());
        assertEquals(0, refreshed.getUsedLimit().compareTo(new BigDecimal("15000.00")));
    }

    @Test
    @DisplayName("resetUsedLimit rucno resetuje samo target agenta")
    void resetUsedLimitPersistsZero() {
        ActuaryInfoDto result = actuaryService.resetUsedLimit(AGENT_MARKO_ID);

        assertEquals(0, result.getUsedLimit().compareTo(BigDecimal.ZERO));

        ActuaryInfo refreshedMarko = actuaryInfoRepository.findByEmployeeId(AGENT_MARKO_ID).orElseThrow();
        ActuaryInfo refreshedJelena = actuaryInfoRepository.findByEmployeeId(AGENT_JELENA_ID).orElseThrow();
        assertEquals(0, refreshedMarko.getUsedLimit().compareTo(BigDecimal.ZERO));
        // R4-1772: scale-4 persistencija → compareTo umesto striktnog equals.
        assertEquals(0, refreshedJelena.getUsedLimit().compareTo(new BigDecimal("999.99")));
    }
}
