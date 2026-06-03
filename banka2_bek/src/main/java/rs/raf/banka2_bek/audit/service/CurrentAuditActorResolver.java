package rs.raf.banka2_bek.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;

/**
 * P2-audit-coverage-1 (R5 1891 / R1 392): razresava IZVRSIOCA (aktora) akcije iz
 * {@link SecurityContextHolder}, a NE vlasnika pogodjenog resursa.
 *
 * <p>Pre ovog resolver-a audit hook-ovi (blockCard / setAccountStatus / unblockCard
 * / limit-change) su belezili {@code card.getClient().getId()} / {@code account.getClient().getId()}
 * kao {@code actorId} — tj. VLASNIKA targeta, ne onoga ko je akciju izvrsio. Posledica:
 * kad zaposleni blokira tudju karticu, audit kaze da je to uradio sam klijent
 * (actorType cesto hardkodiran na "CLIENT"/"EMPLOYEE" nekonzistentno). "Ko je sta uradio"
 * je time bio neupotrebljiv za reviziju.</p>
 *
 * <p>Ovaj resolver mapira email iz JWT principala u (actorId, actorType): EMPLOYEE
 * tabela prvo (zaposleni/admin/supervizor), pa CLIENT tabela. Kad nema auth konteksta
 * (interni poziv / scheduler / unit test bez SecurityContext-a) vraca SYSTEM aktora
 * ({@code actorId=0, actorType="SYSTEM"}) — audit upis i dalje prolazi (actor_id je
 * NOT NULL u semi), samo bez ljudskog izvrsioca.</p>
 */
@Component
@RequiredArgsConstructor
public class CurrentAuditActorResolver {

    /** Aktor kad nema autentifikovanog korisnika (interni/scheduler poziv). */
    public static final AuditActor SYSTEM = new AuditActor(0L, "SYSTEM");

    private final EmployeeRepository employeeRepository;
    private final ClientRepository clientRepository;

    /**
     * (actorId, actorType) trenutno autentifikovanog izvrsioca. EMPLOYEE ima
     * prioritet (employee portal akcije), pa CLIENT (self-service). Bez auth
     * konteksta → {@link #SYSTEM}.
     */
    public AuditActor resolveCurrentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            return SYSTEM;
        }
        String email = extractEmail(auth);
        if (email == null || email.isBlank()) {
            return SYSTEM;
        }

        var employee = employeeRepository.findByEmail(email);
        if (employee.isPresent()) {
            return new AuditActor(employee.get().getId(), "EMPLOYEE");
        }
        var client = clientRepository.findByEmail(email);
        if (client.isPresent()) {
            return new AuditActor(client.get().getId(), "CLIENT");
        }
        return SYSTEM;
    }

    private static String extractEmail(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        return principal.toString();
    }

    /** (actorId, actorType) para — izvrsilac audit-ovane akcije. */
    public record AuditActor(Long actorId, String actorType) {
    }
}
