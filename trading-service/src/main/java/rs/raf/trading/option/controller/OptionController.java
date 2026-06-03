package rs.raf.trading.option.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import rs.raf.trading.common.dto.MessageResponseDto;
import rs.raf.trading.option.dto.OptionChainDto;
import rs.raf.trading.option.dto.OptionDto;
import rs.raf.trading.option.service.OptionGeneratorService;
import rs.raf.trading.option.service.OptionService;

import java.util.List;

/**
 * Endpoint-i za opcije — option chain, detalji, izvrsavanje i generisanje.
 *
 * <p>NAPOMENA (copy-first ekstrakcija, faza 2d-C): {@code option} paket nije
 * imao package-scoped exception handler u monolitu — oslanjao se na globalni
 * {@code GlobalExceptionHandler}. trading-service app-wide
 * {@code TradingGlobalExceptionHandler} preslikava te mapinge
 * ({@code EntityNotFoundException}→404, {@code IllegalArgumentException}→400,
 * {@code IllegalStateException}→403, {@code AccessDeniedException}→403,
 * {@code RuntimeException}→400), pa nov handler nije potreban.
 */
@Tag(name = "Options", description = "Opcije — option chain, detalji, izvrsavanje i generisanje")
@RestController
@RequestMapping("/options")
@RequiredArgsConstructor
public class OptionController {

    private final OptionService optionService;
    private final OptionGeneratorService optionGeneratorService;

    @Operation(summary = "Vraca option chain za akciju",
            description = "Vraca sve opcije za datu akciju, grupisane po settlement datumu.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Option chain uspesno vracen"),
            @ApiResponse(responseCode = "404", description = "Akcija sa datim ID-jem ne postoji",
                    content = @Content(schema = @Schema(implementation = MessageResponseDto.class)))
    })
    @GetMapping
    public ResponseEntity<List<OptionChainDto>> getOptionsForStock(@RequestParam Long stockListingId) {
        return ResponseEntity.ok(optionService.getOptionsForStock(stockListingId));
    }

    @Operation(summary = "Vraca detalje jedne opcije")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Opcija uspesno pronadjena"),
            @ApiResponse(responseCode = "404", description = "Opcija sa datim ID-jem ne postoji",
                    content = @Content(schema = @Schema(implementation = MessageResponseDto.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<OptionDto> getOptionById(@PathVariable Long id) {
        return ResponseEntity.ok(optionService.getOptionById(id));
    }

    @Operation(summary = "Izvrsi opciju",
            description = "Izvrsava opciju (exercise). Samo aktuari mogu izvrsavati opcije.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Opcija uspesno izvrsena"),
            @ApiResponse(responseCode = "400", description = "Opcija nije validna za izvrsavanje",
                    content = @Content(schema = @Schema(implementation = MessageResponseDto.class))),
            @ApiResponse(responseCode = "403", description = "Korisnik nema dozvolu",
                    content = @Content(schema = @Schema(implementation = MessageResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Opcija ne postoji",
                    content = @Content(schema = @Schema(implementation = MessageResponseDto.class)))
    })
    // P2-authz-method-1 (R1 462): opcije su sistemski-generisani, bankarski
    // instrumenti — NEMAJU per-red vlasnika (Option entitet nema userId/ownerId;
    // exercise UVEK operise nad bankinim EMPLOYEE portfoliom u ime banke). "Ownership"
    // u smislu per-opcija-vlasnika ne postoji u modelu; jedina smislena autorizacija
    // je da li je akter ovlascen aktuar/admin. Ranije je metoda bila SAMO
    // authenticated() na HTTP nivou (svaki ulogovan korisnik, ukljucujuci klijenta,
    // je mogao da pogodi servis — koji onda baca AccessDenied). Sad: eksplicitan
    // method-level guard na role zaposlenog (ADMIN/EMPLOYEE — oba tipa aktuara nose
    // EMPLOYEE rolu) PRE poziva servisa (defense-in-depth: klijent NIKAD ne ulazi u
    // servis). Fino-granularni aktuar/aktivan-nalog check ostaje u
    // OptionService.ensureUserCanExerciseOptions.
    @PostMapping("/{id}/exercise")
    @PreAuthorize("hasRole('ADMIN') or hasRole('EMPLOYEE')")
    public ResponseEntity<MessageResponseDto> exerciseOption(
            @PathVariable Long id,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        optionService.exerciseOption(id, userEmail);
        return ResponseEntity.ok(new MessageResponseDto("Opcija uspesno izvrsena."));
    }

    @Operation(summary = "Generiši opcije za sve akcije")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Opcije uspesno generisane"),
            @ApiResponse(responseCode = "403", description = "Korisnik nema ulogu ADMIN",
                    content = @Content(schema = @Schema(implementation = MessageResponseDto.class)))
    })
    @PostMapping("/generate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MessageResponseDto> generateOptions() {
        optionGeneratorService.generateAllOptions();
        return ResponseEntity.ok(new MessageResponseDto("Opcije uspesno generisane za sve akcije."));
    }
}
