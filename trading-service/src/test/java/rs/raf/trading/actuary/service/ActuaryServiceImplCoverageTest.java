package rs.raf.trading.actuary.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import rs.raf.trading.actuary.dto.UpdateActuaryLimitDto;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.actuary.service.implementation.ActuaryServiceImpl;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.security.TradingUserResolver;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Coverage test za preostale grane u ActuaryServiceImpl:
 *  - resetUsedLimit kada aktuarski zapis ne postoji (orElseThrow EntityNotFound)
 *  - getAuthenticatedEmployeeId: resolver baca (nema autentifikacije) — u
 *    trading-service-u logiku "ko je trenutni korisnik" radi TradingUserResolver,
 *    pa se monolitni "principal nije UserDetails / nije authenticated" slucajevi
 *    preslikavaju na resolveCurrent() koji baca IllegalStateException.
 *  - getAuthenticatedEmployeeId: korisnik nije EMPLOYEE rola.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActuaryServiceImplCoverageTest {

    @Mock
    private ActuaryInfoRepository actuaryInfoRepository;

    @Mock
    private BankaCoreClient bankaCoreClient;

    @Mock
    private TradingUserResolver userResolver;

    @InjectMocks
    private ActuaryServiceImpl actuaryService;

    @Test
    @DisplayName("resetUsedLimit throws EntityNotFoundException when actuary record missing")
    void resetUsedLimit_notFound() {
        when(actuaryInfoRepository.findByEmployeeId(999L)).thenReturn(Optional.empty());

        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class,
                () -> actuaryService.resetUsedLimit(999L));

        assertTrue(ex.getMessage().contains("999"));
    }

    @Test
    @DisplayName("updateAgentLimit throws when no authenticated user in security context")
    void updateAgentLimit_noAuthentication() {
        when(userResolver.resolveCurrent())
                .thenThrow(new IllegalStateException("Nema autentifikovanog korisnika u security context-u"));

        UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
        dto.setNeedApproval(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> actuaryService.updateAgentLimit(1L, dto));

        assertTrue(ex.getMessage().contains("autentifikovanog korisnika"));
    }

    @Test
    @DisplayName("updateAgentLimit throws when current user role is not EMPLOYEE")
    void updateAgentLimit_currentUserNotEmployee() {
        // Resolver razresava korisnika kao CLIENT — ne moze biti aktuar.
        when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, "CLIENT"));

        UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
        dto.setDailyLimit(new BigDecimal("1000"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> actuaryService.updateAgentLimit(1L, dto));

        assertTrue(ex.getMessage().contains("not an actuary"));
    }
}
