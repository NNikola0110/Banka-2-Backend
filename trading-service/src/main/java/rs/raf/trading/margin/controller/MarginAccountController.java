package rs.raf.trading.margin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import rs.raf.trading.margin.dto.CreateCompanyMarginAccountDto;
import rs.raf.trading.margin.dto.CreateMarginAccountDto;
import rs.raf.trading.margin.dto.MarginAccountDto;
import rs.raf.trading.margin.dto.MarginTransactionDto;
import rs.raf.trading.margin.service.MarginAccountService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * REST kontroler za margin racune.
 * Endpointovi:
 *   POST /margin-accounts                     - kreiranje margin racuna (klijent)
 *   POST /margin-accounts/company              - kreiranje marznog racuna kompanije (zaposleni)
 *   GET  /margin-accounts/company/{companyId}  - marzni racun kompanije (zaposleni)
 *   GET  /margin-accounts/my                   - moji margin racuni
 *   GET  /margin-accounts/{id}                 - detalji margin racuna
 *   POST /margin-accounts/{id}/deposit         - uplata na margin racun
 *   POST /margin-accounts/{id}/withdraw        - isplata sa margin racuna
 *   GET  /margin-accounts/{id}/transactions    - istorija transakcija
 * Specifikacija: Celina 3 - Margin racuni
 *
 * <p>NAPOMENA (copy-first ekstrakcija, faza 2d-D): monolitni kontroler je sam
 * razresavao klijenta preko {@code ClientRepository}. trading-service nema
 * {@code clients} tabelu — identitet (numericki id + provera da je klijent)
 * sad razresava {@code MarginAccountService} interno preko
 * {@code TradingUserResolver} (banka-core seam). {@code Authentication} parametar
 * ostaje jer ga security filter chain zahteva. {@code margin} paket nije imao
 * package-scoped exception handler — app-wide {@code TradingGlobalExceptionHandler}
 * (faza 2c) preslikava {@code EntityNotFoundException}→404,
 * {@code IllegalArgumentException}→400, {@code IllegalStateException}→403, pa
 * nov handler nije potreban.
 */
@Tag(name = "Margin Account", description = "Margin account management API")
@RestController
@RequestMapping("/margin-accounts")
@RequiredArgsConstructor
public class MarginAccountController {

    private final MarginAccountService marginAccountService;

    /**
     * POST /margin-accounts
     * Kreira novi margin racun za autentifikovanog korisnika.
     */
    @PostMapping
    public ResponseEntity<MarginAccountDto> create(
            @Valid @RequestBody CreateMarginAccountDto dto,
            Authentication authentication) {
        return ResponseEntity.ok(marginAccountService.createForUser(dto));
    }

    /**
     * POST /margin-accounts/company
     * BE-STK-06: kreira marzni racun za kompaniju (Marzni_Racuni.txt §43-55).
     * Samo zaposleni (supervizor/admin); identitet zaposlenog je iz JWT-a
     * (telo {@code employeeId} se ne prihvata).
     */
    @Operation(summary = "Create company margin account",
            description = "Creates a margin account owned by a company. Employee-only. "
                    + "The employee supplies explicit InitialMargin/MaintenanceMargin/BankParticipation. "
                    + "One margin account per company.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Company margin account created"),
            @ApiResponse(responseCode = "400", description = "Validation failure, base account not RSD/active, "
                    + "insufficient funds, or company already has a margin account"),
            @ApiResponse(responseCode = "403", description = "Not authenticated or caller is not an employee")
    })
    @PostMapping("/company")
    public ResponseEntity<MarginAccountDto> createForCompany(
            @Valid @RequestBody CreateCompanyMarginAccountDto dto,
            Authentication authentication) {
        return ResponseEntity.ok(marginAccountService.createForCompany(dto));
    }

    /**
     * GET /margin-accounts/company/{companyId}
     * BE-STK-06: vraca marzni racun kompanije (Marzni_Racuni.txt §61). Zaposleni-only.
     */
    @Operation(summary = "Get company margin account",
            description = "Returns the margin account for a company by companyId. Employee-only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Company margin account",
                    content = @Content(schema = @Schema(implementation = MarginAccountDto.class))),
            @ApiResponse(responseCode = "403", description = "Not authenticated or caller is not an employee"),
            @ApiResponse(responseCode = "404", description = "Company has no margin account")
    })
    @GetMapping("/company/{companyId}")
    public ResponseEntity<MarginAccountDto> getCompanyMarginAccount(
            @Parameter(description = "Company ID") @PathVariable Long companyId,
            Authentication authentication) {
        return ResponseEntity.ok(marginAccountService.getCompanyMarginAccount(companyId));
    }

    /**
     * GET /margin-accounts/my
     * Vraca sve margin racune autentifikovanog korisnika.
     */
    @GetMapping("/my")
    public ResponseEntity<List<MarginAccountDto>> getMyMarginAccounts(Authentication authentication) {
        return ResponseEntity.ok(marginAccountService.getMyMarginAccounts());
    }

    /**
     * GET /margin-accounts/{id}
     * Vraca detalje jednog margin racuna.
     */
    @GetMapping("/{id}")
    public ResponseEntity<MarginAccountDto> getById(@PathVariable Long id, Authentication authentication) {
        List<MarginAccountDto> accounts = marginAccountService.getMyMarginAccounts();
        return accounts.stream()
                .filter(a -> a.getId() != null && a.getId().equals(id))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /margin-accounts/{id}/deposit
     * Uplata sredstava na margin racun.
     * Request body: { "amount": 5000.00 }
     */
    @Operation(summary = "Deposit funds", description = "Deposits funds into a margin account. Unblocks the account if it was blocked. Only the account owner can deposit.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deposit successful"),
            @ApiResponse(responseCode = "400", description = "Amount is missing, zero, or negative"),
            @ApiResponse(responseCode = "403", description = "Not authenticated, caller is not a client, or not the account owner"),
            @ApiResponse(responseCode = "404", description = "Margin account not found")
    })
    @PostMapping("/{id}/deposit")
    public ResponseEntity<Map<String, String>> deposit(
            @Parameter(description = "Margin account ID") @PathVariable Long id,
            @RequestBody Map<String, BigDecimal> body,
            Authentication authentication) {
        BigDecimal amount = body.get("amount");
        marginAccountService.deposit(id, amount);
        return ResponseEntity.ok(Map.of("message", "Deposit successful"));
    }

    /**
     * POST /margin-accounts/{id}/withdraw
     * Isplata sredstava sa margin racuna.
     * Request body: { "amount": 2000.00 }
     */
    @Operation(summary = "Withdraw funds", description = "Withdraws funds from a margin account. The account must be active and the remaining balance must not fall below the maintenance margin. Only the account owner can withdraw.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawal successful"),
            @ApiResponse(responseCode = "400", description = "Amount is missing, zero, negative, or withdrawal would drop below maintenance margin"),
            @ApiResponse(responseCode = "403", description = "Not authenticated, caller is not a client, not the account owner, or account is not active"),
            @ApiResponse(responseCode = "404", description = "Margin account not found")
    })
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<Map<String, String>> withdraw(
            @Parameter(description = "Margin account ID") @PathVariable Long id,
            @RequestBody Map<String, BigDecimal> body,
            Authentication authentication
    ) {
        BigDecimal amount = body.get("amount");
        marginAccountService.withdraw(id, amount);
        return ResponseEntity.ok(Map.of("message", "Withdrawal successful"));
    }

    /**
     * GET /margin-accounts/{id}/transactions
     * Vraca istoriju transakcija za dati margin racun.
     */
    @Operation(summary = "Get transaction history", description = "Returns the transaction history for a margin account, sorted newest first. Only the account owner can view transactions.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of transactions",
                    content = @Content(schema = @Schema(implementation = MarginTransactionDto.class))),
            @ApiResponse(responseCode = "403", description = "Not authenticated, caller is not a client, or not the account owner"),
            @ApiResponse(responseCode = "404", description = "Margin account not found")
    })
    @GetMapping("/{id}/transactions")
    public ResponseEntity<List<MarginTransactionDto>> getTransactions(
            @Parameter(description = "Margin account ID") @PathVariable Long id,
            Authentication authentication) {
        return ResponseEntity.ok(marginAccountService.getTransactions(id));
    }
}
