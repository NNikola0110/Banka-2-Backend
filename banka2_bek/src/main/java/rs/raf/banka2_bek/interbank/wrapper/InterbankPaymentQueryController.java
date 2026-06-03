package rs.raf.banka2_bek.interbank.wrapper;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import rs.raf.banka2_bek.auth.util.UserContext;
import rs.raf.banka2_bek.auth.util.UserResolver;
import rs.raf.banka2_bek.interbank.wrapper.InterbankOtcWrapperDtos.InterbankTransactionDto;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * P1-9 — FE-facing read-only ruta za pracenje inter-bank 2PC / OTC SAGA progresa.
 *
 * <p>{@code OtcInterBankContractsTab} poll-uje {@code GET /interbank/payments/{id}}
 * (vidi {@code OtcInterBankContractsTab.tsx:222,307,337}) da bi pratila SAGA fazu
 * inter-bank exercise-a / placanja. Pre ovog kontrolera, banka-core je imao samo
 * {@code @PostMapping} {@code /interbank} ulaz (inbound protokol), pa je GET vracao
 * 404 — a {@code InterbankAuthFilter} je dodatno trazio X-Api-Key za {@code /interbank/**}
 * (osim {@code /interbank/otc}), pa je browser JWT zahtev dobijao 401 jos pre toga.
 *
 * <p>Ova ruta je JWT-authenticated (kao {@code /interbank/otc/**}); {@code InterbankAuthFilter}
 * je exempt-uje (vidi {@code isInterbankPath}), a {@code GlobalSecurityConfig} je
 * deklarise {@code authenticated()} PRE generic {@code /interbank/** → ROLE_INTERBANK}
 * matcher-a. Vlasnistvo (anti-IDOR) se proverava u servisu.
 */
@RestController
@RequestMapping("/interbank/payments")
@RequiredArgsConstructor
public class InterbankPaymentQueryController {

    private final InterbankOtcWrapperService wrapperService;
    private final UserResolver userResolver;

    @GetMapping("/{id}")
    public ResponseEntity<InterbankTransactionDto> getInterbankTransaction(@PathVariable String id) {
        UserContext ctx = userResolver.resolveCurrent();
        return ResponseEntity.ok(
                wrapperService.getInterbankTransactionView(id, ctx.userId(), normalizeRole(ctx.userRole())));
    }

    /** §P1-9 — JWT role "CLIENT"/"ADMIN"/"EMPLOYEE" → interna "CLIENT"/"EMPLOYEE". */
    private static String normalizeRole(String role) {
        if (role == null) {
            throw new AccessDeniedException("Korisnicka rola nije postavljena");
        }
        if ("CLIENT".equalsIgnoreCase(role)) return "CLIENT";
        return "EMPLOYEE";
    }

    // ── Exception mapping (scoped to ovaj controller) ───────────────────────────

    @RestControllerAdvice(assignableTypes = InterbankPaymentQueryController.class)
    static class ExceptionMapping {

        /** Nepostojeca transakcija/ugovor → 404. */
        @ExceptionHandler(NoSuchElementException.class)
        public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
        }

        /** Resurs ne pripada pozivacu → 403 (anti-IDOR). */
        @ExceptionHandler(AccessDeniedException.class)
        public ResponseEntity<Map<String, String>> handleForbidden(AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", ex.getMessage()));
        }
    }
}
