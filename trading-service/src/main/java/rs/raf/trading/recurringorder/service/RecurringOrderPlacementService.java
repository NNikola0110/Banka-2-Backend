package rs.raf.trading.recurringorder.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.service.OrderService;
import rs.raf.trading.recurringorder.model.RecurringOrder;
import rs.raf.trading.security.TradingPermissionResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * N1 FIX (broken-feature, tx izolacija): kreira Market order iz trajnog naloga u
 * SOPSTVENOJ ({@code REQUIRES_NEW}) transakciji, pod sistemskim Spring Security
 * kontekstom vlasnika naloga.
 *
 * <p><b>Zasto zaseban bean:</b> {@code RecurringOrderService.executeOne} radi u
 * {@code REQUIRES_NEW} tx. Da je {@code orderService.createOrder} pozvan direktno
 * iz nje (REQUIRED → join), svaki neuspeh ({@code AccessDenied}, nedovoljna
 * sredstva, banka-core 4xx) bi oznacio TU tx kao rollback-only, pa bi
 * {@code advanceAndSave(nextRun)} u catch grani pukao sa
 * {@code UnexpectedRollbackException} → {@code nextRun} se NE bi pomerio i nalog
 * bi se vrteo u petlji (ili scheduler-loop catch progutao gresku). Izvlacenjem
 * order-placement-a u {@code REQUIRES_NEW} tx kroz proxy, njegov rollback ostaje
 * IZOLOVAN: {@code executeOne} tx ostaje cista i moze da pomeri {@code nextRun}
 * bez obzira na ishod order-kreiranja (paritet sa {@code SingleOrderExecutor}
 * obrascem za fill izolaciju).
 *
 * <p><b>Sistemski SecurityContext:</b> scheduler thread nema auth.
 * {@code OrderServiceImpl.createOrder} razresava identitet
 * ({@code resolveCurrent}) i autorizaciju ({@code ensureTradingAccess}) iz
 * {@code SecurityContextHolder}-a. Ovde razresimo identitet vlasnika
 * ({@code ownerId/ownerType}) iz banka-core-a u email + permisije i postavimo
 * {@code UsernamePasswordAuthenticationToken} (email + {@code ROLE_<role>} +
 * permisije: {@code TRADE_STOCKS} za klijenta, {@code SUPERVISOR}/{@code AGENT}
 * za zaposlenog) — tacno kako bi {@code TradingJwtAuthenticationFilter} uradio za
 * realan request. Kontekst se UVEK cisti u {@code finally} (scheduler thread se
 * reciklira; zaostao auth bi procurio na sledeci nalog).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecurringOrderPlacementService {

    private final OrderService orderService;
    private final BankaCoreClient bankaCoreClient;
    private final TradingPermissionResolver permissionResolver;

    /**
     * Kreira Market order iz trajnog naloga u sopstvenoj {@code REQUIRES_NEW} tx
     * pod sistemskim kontekstom vlasnika. Mora se pozivati KROZ proxy (iz
     * {@code RecurringOrderService}, ne kroz {@code this}) da bi Spring zaista
     * otvorio novu transakciju.
     *
     * @throws RuntimeException ako se identitet vlasnika ne moze razresiti ili
     *                          {@code createOrder} odbije nalog — pozivalac
     *                          (executeOne) hvata i svejedno pomera {@code nextRun}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void placeMarketOrder(RecurringOrder recurringOrder, long quantity) {
        CreateOrderDto orderDto = new CreateOrderDto();
        orderDto.setOrderType("MARKET");
        orderDto.setDirection(recurringOrder.getDirection());
        orderDto.setListingId(recurringOrder.getListingId());
        orderDto.setQuantity((int) quantity);
        orderDto.setAccountId(recurringOrder.getAccountId());
        orderDto.setAllOrNone(false);
        orderDto.setMargin(false);
        // NE postavljamo otpCode — internalActor=true znaci da OTP guard ne stoji.

        runAsOrderOwner(recurringOrder, () -> orderService.createOrder(orderDto, true));
    }

    private void runAsOrderOwner(RecurringOrder recurringOrder, Runnable action) {
        String role = normalizeOwnerRole(recurringOrder.getOwnerType());
        InternalUserDto owner = bankaCoreClient.getUserById(role, recurringOrder.getOwnerId());
        if (owner == null || owner.email() == null || owner.email().isBlank()) {
            throw new IllegalStateException(
                    "Ne mogu da razresim email vlasnika trajnog naloga id=" + recurringOrder.getId()
                            + " (ownerId=" + recurringOrder.getOwnerId() + ", ownerType=" + role + ")");
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        for (String permission : permissionResolver.resolvePermissions(owner.email())) {
            if (permission != null && !permission.isBlank()) {
                authorities.add(new SimpleGrantedAuthority(permission));
            }
        }

        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContext systemContext = SecurityContextHolder.createEmptyContext();
            systemContext.setAuthentication(new UsernamePasswordAuthenticationToken(
                    owner.email(), null, authorities));
            SecurityContextHolder.setContext(systemContext);
            action.run();
        } finally {
            if (previous != null) {
                SecurityContextHolder.setContext(previous);
            } else {
                SecurityContextHolder.clearContext();
            }
        }
    }

    /**
     * Normalizuje {@code ownerType} trajnog naloga u banka-core role-segment
     * ({@code CLIENT}/{@code EMPLOYEE}) koji {@code getUserById} ocekuje. Owner je
     * kreiran sa {@code me.userRole()} pa je vec CLIENT ili EMPLOYEE; sve sto nije
     * CLIENT tretira se kao EMPLOYEE (banka-core admin lookup ide kroz employees).
     */
    private String normalizeOwnerRole(String ownerType) {
        if (UserRole.CLIENT.equals(ownerType)) {
            return UserRole.CLIENT;
        }
        return UserRole.EMPLOYEE;
    }
}
