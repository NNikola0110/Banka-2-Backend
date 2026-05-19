package rs.raf.trading.order.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.order.model.OrderStatus;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test {@link OrderStatusService} — porten iz monolita (faza 2c, package
 * rename). {@code ActuaryInfo} je trading-service entitet pa nema izmena
 * logike. Mockuje {@code ActuaryInfoRepository}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderStatusService")
class OrderStatusServiceTest {

    @Mock private ActuaryInfoRepository actuaryInfoRepository;

    @InjectMocks
    private OrderStatusService service;

    private ActuaryInfo agentInfo(boolean needApproval, BigDecimal usedLimit, BigDecimal dailyLimit) {
        ActuaryInfo info = new ActuaryInfo();
        info.setActuaryType(ActuaryType.AGENT);
        info.setNeedApproval(needApproval);
        info.setUsedLimit(usedLimit);
        info.setDailyLimit(dailyLimit);
        return info;
    }

    private ActuaryInfo supervisorInfo() {
        ActuaryInfo info = new ActuaryInfo();
        info.setActuaryType(ActuaryType.SUPERVISOR);
        info.setNeedApproval(false);
        return info;
    }

    @Nested
    @DisplayName("CLIENT")
    class ClientRole {

        @Test
        @DisplayName("CLIENT uvek dobija APPROVED")
        void clientIsAlwaysApproved() {
            OrderStatus status = service.determineStatus("CLIENT", 1L, new BigDecimal("1000"));
            assertEquals(OrderStatus.APPROVED, status);
            verifyNoInteractions(actuaryInfoRepository);
        }
    }

    @Nested
    @DisplayName("EMPLOYEE — SUPERVISOR")
    class SupervisorRole {

        @Test
        @DisplayName("SUPERVISOR uvek dobija APPROVED")
        void supervisorIsAlwaysApproved() {
            when(actuaryInfoRepository.findByEmployeeId(2L)).thenReturn(Optional.of(supervisorInfo()));

            OrderStatus status = service.determineStatus("EMPLOYEE", 2L, new BigDecimal("999999"));
            assertEquals(OrderStatus.APPROVED, status);
        }

        @Test
        @DisplayName("EMPLOYEE bez ActuaryInfo → tretira se kao SUPERVISOR, APPROVED")
        void employeeWithoutActuaryInfoIsApproved() {
            when(actuaryInfoRepository.findByEmployeeId(3L)).thenReturn(Optional.empty());

            OrderStatus status = service.determineStatus("EMPLOYEE", 3L, new BigDecimal("1000"));
            assertEquals(OrderStatus.APPROVED, status);
        }
    }

    @Nested
    @DisplayName("EMPLOYEE — AGENT")
    class AgentRole {

        @Test
        @DisplayName("AGENT sa needApproval=true → PENDING")
        void agentNeedApprovalTrue() {
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(
                    Optional.of(agentInfo(true, BigDecimal.ZERO, new BigDecimal("10000"))));

            OrderStatus status = service.determineStatus("EMPLOYEE", 10L, new BigDecimal("1000"));
            assertEquals(OrderStatus.PENDING, status);
        }

        @Test
        @DisplayName("AGENT sa needApproval=false, usedLimit + price > dailyLimit → PENDING")
        void agentOverDailyLimit() {
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(
                    Optional.of(agentInfo(false, new BigDecimal("8000"), new BigDecimal("10000"))));

            OrderStatus status = service.determineStatus("EMPLOYEE", 10L, new BigDecimal("3000"));
            assertEquals(OrderStatus.PENDING, status);
        }

        @Test
        @DisplayName("AGENT sa needApproval=false, usedLimit + price == dailyLimit → APPROVED")
        void agentExactlyAtDailyLimit() {
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(
                    Optional.of(agentInfo(false, new BigDecimal("7000"), new BigDecimal("10000"))));

            OrderStatus status = service.determineStatus("EMPLOYEE", 10L, new BigDecimal("3000"));
            assertEquals(OrderStatus.APPROVED, status);
        }

        @Test
        @DisplayName("AGENT sa needApproval=false, usedLimit + price < dailyLimit → APPROVED")
        void agentUnderDailyLimit() {
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(
                    Optional.of(agentInfo(false, new BigDecimal("1000"), new BigDecimal("10000"))));

            OrderStatus status = service.determineStatus("EMPLOYEE", 10L, new BigDecimal("2000"));
            assertEquals(OrderStatus.APPROVED, status);
        }

        @Test
        @DisplayName("AGENT sa null usedLimit → tretira se kao 0")
        void agentWithNullUsedLimit() {
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(
                    Optional.of(agentInfo(false, null, new BigDecimal("10000"))));

            OrderStatus status = service.determineStatus("EMPLOYEE", 10L, new BigDecimal("5000"));
            assertEquals(OrderStatus.APPROVED, status);
        }

        @Test
        @DisplayName("AGENT sa needApproval=true ignorise limit — uvek PENDING")
        void agentNeedApprovalIgnoresLimit() {
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(
                    Optional.of(agentInfo(true, BigDecimal.ZERO, new BigDecimal("100000"))));

            OrderStatus status = service.determineStatus("EMPLOYEE", 10L, new BigDecimal("1"));
            assertEquals(OrderStatus.PENDING, status);
        }
    }

    // ── Extended edge cases (porteno iz OrderStatusServiceExtendedTest) ──────

    @Nested
    @DisplayName("getAgentInfo")
    class GetAgentInfo {

        @Test
        @DisplayName("returns ActuaryInfo when present")
        void returnsWhenPresent() {
            ActuaryInfo info = new ActuaryInfo();
            info.setEmployeeId(10L);
            info.setActuaryType(ActuaryType.AGENT);
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.of(info));

            Optional<ActuaryInfo> result = service.getAgentInfo(10L);

            assertTrue(result.isPresent());
            assertEquals(ActuaryType.AGENT, result.get().getActuaryType());
        }

        @Test
        @DisplayName("returns empty when no ActuaryInfo exists")
        void returnsEmptyWhenAbsent() {
            when(actuaryInfoRepository.findByEmployeeId(99L)).thenReturn(Optional.empty());

            Optional<ActuaryInfo> result = service.getAgentInfo(99L);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("determineStatus - edge cases")
    class DetermineStatusEdgeCases {

        @Test
        @DisplayName("AGENT with null dailyLimit treated as 0, any price -> PENDING")
        void agentNullDailyLimit_pendingForAnyPrice() {
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(
                    Optional.of(agentInfo(false, BigDecimal.ZERO, null)));

            OrderStatus status = service.determineStatus("EMPLOYEE", 10L, new BigDecimal("1"));

            assertEquals(OrderStatus.PENDING, status);
        }

        @Test
        @DisplayName("AGENT with both null usedLimit and null dailyLimit, price=0 -> APPROVED")
        void agentBothNull_priceZero_approved() {
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(
                    Optional.of(agentInfo(false, null, null)));

            OrderStatus status = service.determineStatus("EMPLOYEE", 10L, BigDecimal.ZERO);

            assertEquals(OrderStatus.APPROVED, status);
        }

        @Test
        @DisplayName("AGENT exceeds limit by 0.01 -> PENDING")
        void agentExceedsBySmallAmount_pending() {
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(
                    Optional.of(agentInfo(false, new BigDecimal("9999.99"), new BigDecimal("10000.00"))));

            OrderStatus status = service.determineStatus("EMPLOYEE", 10L, new BigDecimal("0.02"));

            assertEquals(OrderStatus.PENDING, status);
        }

        @Test
        @DisplayName("AGENT with very large price within limit -> APPROVED")
        void agentLargePrice_withinLimit() {
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(
                    Optional.of(agentInfo(false, BigDecimal.ZERO, new BigDecimal("99999999"))));

            OrderStatus status = service.determineStatus("EMPLOYEE", 10L, new BigDecimal("50000000"));

            assertEquals(OrderStatus.APPROVED, status);
        }

        @Test
        @DisplayName("CLIENT role never checks ActuaryInfo repository")
        void clientNeverChecksRepository() {
            OrderStatus status = service.determineStatus("CLIENT", 999L, new BigDecimal("999999"));

            assertEquals(OrderStatus.APPROVED, status);
            verifyNoInteractions(actuaryInfoRepository);
        }

        @Test
        @DisplayName("unknown role is treated as non-CLIENT, checks ActuaryInfo")
        void unknownRole_checksActuaryInfo() {
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.empty());

            OrderStatus status = service.determineStatus("UNKNOWN_ROLE", 10L, new BigDecimal("100"));

            assertEquals(OrderStatus.APPROVED, status);
        }
    }
}
