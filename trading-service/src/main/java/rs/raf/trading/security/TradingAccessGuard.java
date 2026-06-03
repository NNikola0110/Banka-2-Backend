package rs.raf.trading.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;

/**
 * P1-recurring-watchlist-1 (R1-242) — reusable trading-access guard.
 *
 * <p>Do sada je ekvivalentna provera zivela kao privatni {@code ensureTradingAccess}
 * u {@code OrderServiceImpl} (poziva se na {@code POST /orders}). Trajni nalozi
 * ({@code POST /recurring-orders}) NISU prolazili kroz tu proveru pri KREIRANJU:
 * klijent bez {@code TRADE_STOCKS} permisije je mogao da napravi DCA nalog koji bi
 * potom na svakom scheduler ciklusu pucao na placement-time {@code AccessDenied}
 * ({@code RecurringOrderPlacementService} → {@code createOrder} → {@code ensureTradingAccess}),
 * trosio resurse i nikad ne kupio — uz tihu zabunu korisnika ("napravio sam nalog,
 * a nista se ne desava"). Ovaj guard pomera proveru na trenutak kreiranja (fail-fast)
 * uz paritet sa order-endpoint logikom.
 *
 * <p>Cisto in-process — cita postojeci Spring Security context (autoritete koje je
 * {@code TradingJwtAuthenticationFilter} vec popunio). Bez wire-protokol izmene.
 */
@Component
public class TradingAccessGuard {

    /**
     * Baca {@link AccessDeniedException} (→ 403) ako trenutni korisnik nema pravo
     * trgovine hartijama. Paritet sa {@code OrderServiceImpl.ensureTradingAccess}:
     * <ul>
     *   <li>CLIENT mora imati {@code TRADE_STOCKS} permisiju;</li>
     *   <li>EMPLOYEE mora imati {@code SUPERVISOR}/{@code ADMIN}/{@code AGENT}
     *       (ili {@code ROLE_ADMIN}) autoritet.</li>
     * </ul>
     */
    public void ensureTradingAccess(UserContext user) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new AccessDeniedException("Niste autentifikovani.");
        }
        String role = user.userRole();
        if (UserRole.isClient(role)) {
            boolean hasTradeStocks = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch("TRADE_STOCKS"::equals);
            if (!hasTradeStocks) {
                throw new AccessDeniedException(
                        "Nemate dozvolu za trgovinu hartijama (TRADE_STOCKS permisija nije dodeljena).");
            }
            return;
        }
        if (UserRole.isEmployee(role)) {
            boolean canTrade = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(a -> UserRole.SUPERVISOR.equals(a)
                            || UserRole.ADMIN.equals(a)
                            || UserRole.AGENT.equals(a)
                            || UserRole.ROLE_ADMIN.equals(a));
            if (!canTrade) {
                throw new AccessDeniedException(
                        "Zaposleni mora imati SUPERVISOR, ADMIN ili AGENT autoritet za trgovinu.");
            }
            return;
        }
        throw new AccessDeniedException("Nepoznata uloga ne moze da trguje hartijama.");
    }
}
