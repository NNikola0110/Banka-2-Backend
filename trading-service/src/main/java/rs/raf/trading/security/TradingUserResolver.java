package rs.raf.trading.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;

import java.time.Duration;

/**
 * Most izmedju JWT identiteta i trgovinskog domena.
 *
 * JWT izdat od banka-core nosi samo email u {@code sub} claim-u, a trgovinske
 * entitete (Order, Portfolio, OtcOffer ...) cuvaju numericki {@code userId}
 * (clients.id / employees.id) + {@code userRole}. Ovaj resolver razresava
 * email iz security context-a u {@link UserContext} preko banka-core internog
 * API-ja ({@link BankaCoreClient#getUserByEmail}).
 *
 * email -> UserContext mapiranje se kesira (Caffeine, 5 min): numericki id
 * korisnika je nepromenljiv, pa je kes bezbedan; TTL samo ogranicava staleness
 * {@code active} flaga.
 */
@Component
public class TradingUserResolver {

    private final BankaCoreClient bankaCoreClient;

    /** email -> resolve-ovani identitet. */
    private final Cache<String, UserContext> userContextCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    /** "userRole|userId" -> ime i prezime. */
    private final Cache<String, String> nameCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public TradingUserResolver(BankaCoreClient bankaCoreClient) {
        this.bankaCoreClient = bankaCoreClient;
    }

    /**
     * Razresava identitet (numericki id + rola) trenutno autentifikovanog
     * korisnika iz Spring Security context-a.
     *
     * @throws IllegalStateException ako nema autentifikacije ili banka-core
     *                               ne moze da razresi email
     */
    public UserContext resolveCurrent() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            throw new IllegalStateException("Nema autentifikovanog korisnika u security context-u");
        }
        String email = auth.getName();
        return userContextCache.get(email, this::lookupUserContext);
    }

    /**
     * Formatira ime + prezime za datu kombinaciju (userId, userRole).
     * Ako korisnik nije pronadjen ili banka-core vrati gresku, vraca "Unknown"
     * (rezilijentno, kao monolitov {@code resolveUserName}).
     */
    public String resolveName(Long userId, String userRole) {
        if (userId == null || userRole == null) {
            return "Unknown";
        }
        return nameCache.get(userRole + "|" + userId, key -> lookupName(userId, userRole));
    }

    private UserContext lookupUserContext(String email) {
        try {
            InternalUserDto dto = bankaCoreClient.getUserByEmail(email);
            return new UserContext(dto.userId(), dto.userRole());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Autentifikovani korisnik nije pronadjen: " + email, e);
        }
    }

    private String lookupName(Long userId, String userRole) {
        try {
            InternalUserDto dto = bankaCoreClient.getUserById(userRole, userId);
            return dto.firstName() + " " + dto.lastName();
        } catch (RuntimeException e) {
            return "Unknown";
        }
    }
}
