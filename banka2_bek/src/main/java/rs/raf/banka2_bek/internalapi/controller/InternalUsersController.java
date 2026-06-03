package rs.raf.banka2_bek.internalapi.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.banka2_bek.internalapi.service.InternalLookupService;

import java.util.List;

/**
 * Interni REST API za razresavanje identiteta korisnika (trading-service):
 *   GET /internal/users/by-email/{email}  — identitet po email-u
 *   GET /internal/users/{userRole}/{id}   — identitet po roli + id-u
 *   GET /internal/employees               — filtrirana lista zaposlenih
 * trading-service JWT nosi samo email — ove rute mu daju numericki id + rolu.
 * Sve rute su zasticene X-Internal-Key (InternalAuthFilter + ROLE_INTERNAL).
 *
 * Napomena: postojeca ruta GET /internal/users/{email}/permissions (u
 * InternalFundsController) se ne dira; literali {@code by-email} i
 * {@code permissions} ne kolidiraju sa {userRole}/{id} segmentima.
 */
@RestController
@RequestMapping("/internal")
public class InternalUsersController {

    private final InternalLookupService lookupService;

    public InternalUsersController(InternalLookupService lookupService) {
        this.lookupService = lookupService;
    }

    /**
     * Vraca identitet korisnika (id + rola) za dati email.
     */
    @GetMapping("/users/by-email/{email}")
    public ResponseEntity<InternalUserDto> getUserByEmail(@PathVariable String email) {
        return ResponseEntity.ok(lookupService.getUserByEmail(email));
    }

    /**
     * OT-1061: vraca id-eve svih aktivnih supervizora. trading-service ga koristi
     * da razresi primaoce tax-FX-failure in-app notifikacije (nema lokalnu listu
     * supervizora). Literal {@code supervisors} segment ne kolidira sa
     * {@code {userRole}/{id}} (koja trazi DVA segmenta) niti sa {@code by-email/{email}}.
     */
    @GetMapping("/users/supervisors")
    public ResponseEntity<List<Long>> getSupervisorIds() {
        return ResponseEntity.ok(lookupService.getSupervisorIds());
    }

    /**
     * Vraca identitet korisnika (id + rola) za datu rolu + id.
     */
    @GetMapping("/users/{userRole}/{id}")
    public ResponseEntity<InternalUserDto> getUserById(@PathVariable String userRole,
                                                       @PathVariable Long id) {
        return ResponseEntity.ok(lookupService.getUserById(userRole, id));
    }

    /**
     * Vraca zaposlene filtrirane po opcionim atributima (firstName / lastName /
     * email / position — case-insensitive contains). Bez parametara → svi.
     * Podrzava actuary domen koji posle ekstrakcije filtrira zaposlene.
     */
    @GetMapping("/employees")
    public ResponseEntity<List<InternalUserDto>> getEmployees(
            @RequestParam(required = false) String firstName,
            @RequestParam(required = false) String lastName,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String position) {
        return ResponseEntity.ok(lookupService.findEmployees(firstName, lastName, email, position));
    }
}
