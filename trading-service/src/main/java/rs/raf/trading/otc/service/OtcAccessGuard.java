package rs.raf.trading.otc.service;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.security.TradingUserResolver;

/**
 * P1-authz-idor-1 — zajednicki authz guard za pojedinacne OTC resurse
 * (negotiation-history, saga-log) koji se citaju po {@code id}-u.
 *
 * <p>Single-ID OTC lookup-i (npr. {@code GET /otc/negotiation-history/{id}},
 * {@code GET /otc/saga/{sagaId}}) su do sada bili samo {@code authenticated()} —
 * svaki CLIENT/SUPERVISOR je mogao da procita tudju cenu/premiju/istoriju
 * pregovora ili tok saga-e enumeracijom id-a (IDOR, OPEN_TASKS R1 208 / R1 217).
 * Ovaj guard razresava trenutni identitet i dozvoljava pristup samo:
 * <ul>
 *   <li>ucesniku resursa (buyer ili seller), ili</li>
 *   <li>adminu / supervizoru (oversight pregled, paritet sa paginiranom
 *       {@code findWithFilters} putanjom koja vec zove {@code ensureSupervisorOrAdmin}).</li>
 * </ul>
 *
 * <p>Bez wire-protokol izmene — cisto in-process provera nad postojecim
 * security context-om i banka-core identitet seam-om.
 */
@Component
public class OtcAccessGuard {

    private final TradingUserResolver userResolver;

    public OtcAccessGuard(TradingUserResolver userResolver) {
        this.userResolver = userResolver;
    }

    /** Trenutni razreseni identitet (numericki id + rola) ili throw ako nema autentikacije. */
    public UserContext currentUser() {
        return userResolver.resolveCurrent();
    }

    /** True ako trenutni pozivalac ima ADMIN ili SUPERVISOR authority (oversight). */
    public boolean isAdminOrSupervisor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ADMIN".equals(a)
                        || "SUPERVISOR".equals(a)
                        || "ROLE_ADMIN".equals(a)
                        || "ROLE_SUPERVISOR".equals(a));
    }

    /**
     * Dozvoljava pristup ako je trenutni korisnik ucesnik (buyer/seller) ILI
     * admin/supervizor. Inace baca {@link AccessDeniedException} (mapira se na 403).
     *
     * @param buyerId    numericki id kupca resursa
     * @param buyerRole  rola kupca ("CLIENT"/"EMPLOYEE")
     * @param sellerId   numericki id prodavca resursa
     * @param sellerRole rola prodavca
     * @param resourceLabel kratak naziv resursa za poruku (npr. "istorije pregovora")
     */
    public void ensureParticipantOrOversight(Long buyerId, String buyerRole,
                                             Long sellerId, String sellerRole,
                                             String resourceLabel) {
        if (isAdminOrSupervisor()) {
            return;
        }
        UserContext me = currentUser();
        boolean isBuyer = buyerId != null && buyerId.equals(me.userId())
                && buyerRole != null && buyerRole.equals(me.userRole());
        boolean isSeller = sellerId != null && sellerId.equals(me.userId())
                && sellerRole != null && sellerRole.equals(me.userRole());
        if (!isBuyer && !isSeller) {
            throw new AccessDeniedException(
                    "Niste ucesnik ovog OTC resursa (" + resourceLabel + ").");
        }
    }
}
