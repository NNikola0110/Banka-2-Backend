package rs.raf.banka2_bek.client.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rs.raf.banka2_bek.client.dto.ClientResponseDto;
import rs.raf.banka2_bek.client.dto.CreateClientRequestDto;
import rs.raf.banka2_bek.client.dto.UpdateClientRequestDto;
import rs.raf.banka2_bek.client.service.ClientService;

@Tag(name = "Client", description = "Client management API")
@RestController
@RequestMapping("/clients")
@RequiredArgsConstructor
public class ClientController {
///
    private final ClientService clientService;

    @Operation(summary = "Create client", description = "Employee creates a new client")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Client created"),
            @ApiResponse(responseCode = "400", description = "Validation error or duplicate email")
    })
    @PostMapping
    public ResponseEntity<ClientResponseDto> createClient(@Valid @RequestBody CreateClientRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(clientService.createClient(request));
    }

    @Operation(summary = "List clients", description = "Returns paginated clients with optional filters")
    @GetMapping
    public ResponseEntity<Page<ClientResponseDto>> getClients(
            @Parameter(description = "Page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "10") int limit,
            @Parameter(description = "Filter by first name") @RequestParam(required = false) String firstName,
            @Parameter(description = "Filter by last name") @RequestParam(required = false) String lastName,
            @Parameter(description = "Filter by email") @RequestParam(required = false) String email,
            @Parameter(description = "Unified search across first name, last name, email and phone") @RequestParam(required = false) String search) {
        return ResponseEntity.ok(clientService.getClients(page, limit, firstName, lastName, email, search));
    }

    /**
     * Self-lookup za autentifikovanog klijenta. Vraca njegov vlastiti Client zapis
     * po JWT principal email-u. CLIENT moze; ADMIN/EMPLOYEE ne mogu (oni
     * koriste /clients ili /clients/{id}). Razlog: AuthContext na FE-u (CLIENT
     * login flow) treba clientove numericki id (JWT nema id claim) — pre ovog
     * endpoint-a FE je zvao /clients?email=, sto je trazio role ADMIN/EMPLOYEE
     * (vidi GlobalSecurityConfig "/clients/**") pa klijent dobijao 403 i fallback
     * userId=0 koji rusi OTC operacije.
     */
    @Operation(summary = "Self lookup za prijavljenog klijenta")
    @GetMapping("/me")
    public ResponseEntity<ClientResponseDto> getMyClient(java.security.Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(clientService.getClientByEmail(principal.getName()));
    }

    @Operation(summary = "Get client by ID")
    @GetMapping("/{id}")
    public ResponseEntity<ClientResponseDto> getClientById(@PathVariable Long id) {
        return ResponseEntity.ok(clientService.getClientById(id));
    }

    @Operation(summary = "Update client", description = "Updates client details (except password and email)")
    @PutMapping("/{id}")
    public ResponseEntity<ClientResponseDto> updateClient(
            @PathVariable Long id,
            @Valid @RequestBody UpdateClientRequestDto request) {
        return ResponseEntity.ok(clientService.updateClient(id, request));
    }

    /**
     * T4A-017: supervizor menja TRADE_STOCKS permisiju za klijenta.
     * Spec C3 §6 / C4 §31: samo klijenti sa permisijom mogu da trgovuju hartijama.
     */
    @Operation(summary = "Set client trading permission", description = "Supervizor/admin menja canTradeStocks flag")
    @PatchMapping("/{id}/trading")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    public ResponseEntity<ClientResponseDto> setTradingPermission(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, Boolean> body) {
        Boolean canTrade = body.get("canTradeStocks");
        if (canTrade == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(clientService.setTradingPermission(id, canTrade));
    }
}
