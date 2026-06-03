package rs.raf.trading.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.order.model.OrderStatus;

import java.math.BigDecimal;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderStatusService {

    private final ActuaryInfoRepository actuaryInfoRepository;

    /**
     * Determines the initial status of an order based on who is creating it.
     *
     * <p>CLIENT → APPROVED. EMPLOYEE who is SUPERVISOR → APPROVED. EMPLOYEE who
     * is AGENT with needApproval=true → PENDING. AGENT over dailyLimit → PENDING,
     * under (or unlimited) → APPROVED.
     *
     * <p><b>§66 / R1-183 / R3-1543:</b> {@code approximatePrice} se ovde prosledjuje
     * VEC konvertovan u RSD (limit/usedLimit su izrazeni u jednoj valuti — dinarima;
     * konverzija ide mid-rate-om bez provizije, vidi {@code OrderServiceImpl}).
     * Tako agent na USD hartiji ne probija RSD limit ~117×.
     *
     * <p><b>R2-1356:</b> AGENT sa {@code dailyLimit==null} se tretira kao
     * NEOGRANICEN (kao i supervizor cija je {@code dailyLimit} null u seed-u), NE
     * kao ZERO — inace bi novopostavljen agent bez limita dobijao SVAKI order na
     * PENDING (de-facto zamrznut).
     *
     * <p><b>R1-184 (fail-closed):</b> EMPLOYEE bez {@code ActuaryInfo} koji nosi
     * autoritet {@code AGENT} (a ne SUPERVISOR/ADMIN) tretira se kao AGENT kome
     * treba odobrenje (PENDING) — NE kao supervizor (fail-open). Tek
     * SUPERVISOR/ADMIN autoritet (ili odsustvo AGENT-a) daje APPROVED.
     */
    public OrderStatus determineStatus(String userRole, Long userId, BigDecimal approximatePrice) {
        if (UserRole.isClient(userRole)) {
            return OrderStatus.APPROVED;
        }

        // EMPLOYEE: check ActuaryInfo
        Optional<ActuaryInfo> actuaryOpt = actuaryInfoRepository.findByEmployeeId(userId);
        if (actuaryOpt.isEmpty()) {
            // R1-184: bez ActuaryInfo zapisa — ne tretiramo automatski kao
            // supervizora (fail-open). Ako security context nosi AGENT autoritet
            // (a ne SUPERVISOR/ADMIN), order ide na PENDING (fail-closed) dok se
            // ActuaryInfo ne provizionira. Inace (supervizor/admin/nema AGENT-a)
            // ostaje APPROVED.
            return carriesAgentAuthorityButNotSupervisor()
                    ? OrderStatus.PENDING
                    : OrderStatus.APPROVED;
        }

        ActuaryInfo actuary = actuaryOpt.get();
        if (actuary.getActuaryType() == ActuaryType.SUPERVISOR) {
            return OrderStatus.APPROVED;
        }

        // AGENT logic
        if (actuary.isNeedApproval()) {
            return OrderStatus.PENDING;
        }

        // R2-1356: null dailyLimit = neogranicen agent (nema postavljen limit) →
        // ne forsiramo PENDING na svaki order.
        if (actuary.getDailyLimit() == null) {
            return OrderStatus.APPROVED;
        }

        BigDecimal usedLimit = actuary.getUsedLimit() != null ? actuary.getUsedLimit() : BigDecimal.ZERO;
        BigDecimal dailyLimit = actuary.getDailyLimit();

        if (usedLimit.add(approximatePrice).compareTo(dailyLimit) > 0) {
            return OrderStatus.PENDING;
        }

        return OrderStatus.APPROVED;
    }

    /**
     * R1-184: cita Spring Security autoritete trenutnog principal-a i vraca
     * {@code true} ako nosi {@code AGENT} ali NE {@code SUPERVISOR}/{@code ADMIN}/
     * {@code ROLE_ADMIN}. Koristi se kao fail-closed default kada zaposleni nema
     * jos provizioniran {@code ActuaryInfo} zapis.
     */
    private boolean carriesAgentAuthorityButNotSupervisor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        boolean hasAgent = false;
        boolean hasSupervisor = false;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (UserRole.AGENT.equals(a)) {
                hasAgent = true;
            } else if (UserRole.SUPERVISOR.equals(a)
                    || UserRole.ADMIN.equals(a)
                    || UserRole.ROLE_ADMIN.equals(a)) {
                hasSupervisor = true;
            }
        }
        return hasAgent && !hasSupervisor;
    }

    /**
     * Returns the ActuaryInfo for an AGENT employee if they exist, otherwise empty.
     * Used by OrderServiceImpl to update usedLimit after an APPROVED order.
     */
    public Optional<ActuaryInfo> getAgentInfo(Long userId) {
        return actuaryInfoRepository.findByEmployeeId(userId);
    }
}
