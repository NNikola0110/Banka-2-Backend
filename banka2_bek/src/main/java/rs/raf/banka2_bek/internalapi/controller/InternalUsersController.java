package rs.raf.banka2_bek.internalapi.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.banka2_bek.internalapi.service.InternalLookupService;

/**
 * Interni REST API za razresavanje identiteta korisnika (trading-service).
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
     * Vraca identitet korisnika (id + rola) za datu rolu + id.
     */
    @GetMapping("/users/{userRole}/{id}")
    public ResponseEntity<InternalUserDto> getUserById(@PathVariable String userRole,
                                                       @PathVariable Long id) {
        return ResponseEntity.ok(lookupService.getUserById(userRole, id));
    }
}
