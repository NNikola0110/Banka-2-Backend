package rs.raf.trading.otc.service;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;

/**
 * R1 783 / R2 1443: jedinstven izvor istine za OTC role/permisija gate
 * ({@code ensureOtcAccess}).
 *
 * <p>Spec Celina 4 (Nova) §145-148: OTC je dozvoljen SAMO supervizorima (od
 * zaposlenih — agenti su iskljuceni) i klijentima sa {@code TRADE_STOCKS}
 * permisijom. Spring SecurityConfig vec hvata role na HTTP nivou; ovaj poziv je
 * defense-in-depth za slucaj da neko zaobidje filter (npr. test sa
 * {@code @WithMockUser}).
 *
 * <p><b>Zasto static utility, a ne bean:</b> ranije je ista logika bila kopirana
 * verbatim u {@code OtcService} i {@code OtcExerciseSagaOrchestrator} jer bi
 * direktna zavisnost orkestrator → {@code OtcService} zatvorila ciklus. Static
 * stateless metoda nad postojecim {@link SecurityContextHolder}-om nema bean
 * zavisnost, pa je oba pozivaoca mogu deliti bez ciklusa.
 */
public final class OtcAccessPolicy {

    private OtcAccessPolicy() {
    }

    /**
     * Garantuje da trenutni korisnik sme da pristupi OTC-u. Baca
     * {@link AccessDeniedException} (→ 403) inace.
     *
     * @param user razreseni identitet (rola "CLIENT"/"EMPLOYEE")
     */
    public static void ensureOtcAccess(UserContext user) {
        String role = user.userRole();
        if (UserRole.isClient(role)) {
            // Celina 4: OTC je za "klijente sa permisijama za trgovinu" → TRADE_STOCKS.
            Authentication auth = requireAuth();
            boolean hasTradeStocks = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch("TRADE_STOCKS"::equals);
            if (!hasTradeStocks) {
                throw new AccessDeniedException(
                        "Nemate dozvolu za OTC trgovinu (TRADE_STOCKS permisija nije dodeljena).");
            }
            return;
        }
        if (UserRole.isEmployee(role)) {
            // Od zaposlenih samo supervizori (admini su uvek supervizori); agent → 403.
            Authentication auth = requireAuth();
            boolean isSupervisor = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(a -> "ADMIN".equals(a)
                            || "SUPERVISOR".equals(a)
                            || "ROLE_ADMIN".equals(a)
                            || "ROLE_SUPERVISOR".equals(a));
            if (!isSupervisor) {
                throw new AccessDeniedException(
                        "OTC je dozvoljen samo supervizorima i klijentima (po Celini 4 Nova).");
            }
            return;
        }
        throw new AccessDeniedException("Nepoznata uloga ne moze pristupiti OTC-u.");
    }

    private static Authentication requireAuth() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new AccessDeniedException("Niste autentifikovani.");
        }
        return auth;
    }
}
