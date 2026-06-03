package rs.raf.banka2_bek.card.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.card.dto.CardLimitUpdateDto;
import rs.raf.banka2_bek.card.dto.CardResponseDto;
import rs.raf.banka2_bek.card.dto.CreateCardRequestDto;
import rs.raf.banka2_bek.card.model.CardCategory;
import rs.raf.banka2_bek.card.model.CardRequest;
import rs.raf.banka2_bek.card.model.CardType;
import rs.raf.banka2_bek.card.repository.CardRequestRepository;
import rs.raf.banka2_bek.card.service.CardService;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Tag(name = "Cards", description = "Card management API")
@RestController
@RequestMapping("/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardService cardService;
    private final CardRequestRepository cardRequestRepository;
    private final AccountRepository accountRepository;
    private final ClientRepository clientRepository;

    @Operation(summary = "Create card", description = "Client requests a new card for their account")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Card created"),
            @ApiResponse(responseCode = "400", description = "Max cards reached or invalid account")
    })
    @PostMapping
    public ResponseEntity<CardResponseDto> createCard(@Valid @RequestBody CreateCardRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cardService.createCard(request));
    }

    @Operation(summary = "Get my cards", description = "Returns all cards for authenticated client")
    @GetMapping
    public ResponseEntity<List<CardResponseDto>> getMyCards() {
        return ResponseEntity.ok(cardService.getMyCards());
    }

    @Operation(summary = "Get cards by account", description = "Returns all cards for a specific account (employee portal)")
    @GetMapping("/account/{accountId}")
    public ResponseEntity<List<CardResponseDto>> getCardsByAccount(@PathVariable Long accountId) {
        return ResponseEntity.ok(cardService.getCardsByAccount(accountId));
    }

    @Operation(summary = "Block card", description = "Client blocks their own card")
    @PatchMapping("/{id}/block")
    public ResponseEntity<CardResponseDto> blockCard(@PathVariable Long id) {
        return ResponseEntity.ok(cardService.blockCard(id));
    }

    @Operation(summary = "Unblock card", description = "Employee unblocks a blocked card. Spec C2: klijent NE moze odblokirati, samo zaposleni.")
    @PatchMapping("/{id}/unblock")
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    public ResponseEntity<CardResponseDto> unblockCard(@PathVariable Long id) {
        return ResponseEntity.ok(cardService.unblockCard(id));
    }

    @Operation(summary = "Deactivate card", description = "Employee permanently deactivates a card")
    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    public ResponseEntity<CardResponseDto> deactivateCard(@PathVariable Long id) {
        return ResponseEntity.ok(cardService.deactivateCard(id));
    }

    /**
     * C2 Sc32 (§spec): pokusaj aktivacije kartice. Deaktivirana kartica je
     * ireverzibilna — vraca poruku "Kartica je deaktivirana i ne moze se ponovo
     * aktivirati" (403 kroz CardExceptionHandler IllegalState handler).
     */
    @Operation(summary = "Activate card",
            description = "Pokusaj aktivacije kartice. Deaktivirana kartica se ne moze ponovo aktivirati (Sc32).")
    @PatchMapping("/{id}/activate")
    public ResponseEntity<CardResponseDto> activateCard(@PathVariable Long id) {
        return ResponseEntity.ok(cardService.activateCard(id));
    }

    @Operation(summary = "Update card limit")
    @PatchMapping("/{id}/limit")
    public ResponseEntity<CardResponseDto> updateCardLimit(
            @PathVariable Long id,
            @Valid @RequestBody CardLimitUpdateDto request) {
        return ResponseEntity.ok(cardService.updateCardLimit(id, request.getCardLimit()));
    }

    /**
     * Dopuna INTERNET_PREPAID kartice — prebacuje sredstva sa povezanog Account-a
     * na Card.prepaidBalance. Klijent moze samo svoju karticu; employee/admin bilo koju.
     */
    @Operation(summary = "Top-up prepaid card", description = "Prebacuje iznos sa Account-a na Card.prepaidBalance (samo INTERNET_PREPAID).")
    @PostMapping("/{id}/top-up")
    public ResponseEntity<CardResponseDto> topUpPrepaidCard(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            // P1-idempotency-1 (R5-1849): opcioni klijent-generisan kljuc za
            // replay-safe dopunu. Kad nije prosledjen, ponasanje je nepromenjeno.
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Long sourceAccountId = body.get("sourceAccountId") != null
                ? Long.valueOf(String.valueOf(body.get("sourceAccountId"))) : null;
        if (sourceAccountId == null) {
            throw new IllegalArgumentException("sourceAccountId je obavezan.");
        }
        BigDecimal amount = body.get("amount") != null
                ? new BigDecimal(String.valueOf(body.get("amount"))) : null;
        return ResponseEntity.ok(cardService.topUpPrepaidCard(id, sourceAccountId, amount, idempotencyKey));
    }

    /**
     * Povlacenje sredstava sa INTERNET_PREPAID kartice nazad na Account.
     */
    @Operation(summary = "Withdraw from prepaid card", description = "Skida iznos sa Card.prepaidBalance i dodaje na ciljni Account.")
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<CardResponseDto> withdrawFromPrepaidCard(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Long targetAccountId = body.get("targetAccountId") != null
                ? Long.valueOf(String.valueOf(body.get("targetAccountId"))) : null;
        if (targetAccountId == null) {
            throw new IllegalArgumentException("targetAccountId je obavezan.");
        }
        BigDecimal amount = body.get("amount") != null
                ? new BigDecimal(String.valueOf(body.get("amount"))) : null;
        return ResponseEntity.ok(cardService.withdrawFromPrepaidCard(id, targetAccountId, amount, idempotencyKey));
    }

    // ===== Card Requests (klijent podnosi zahtev, admin odobrava) =====

    @PostMapping("/requests")
    @Transactional
    public ResponseEntity<Map<String, Object>> submitCardRequest(@RequestBody Map<String, Object> body) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Client client = clientRepository.findByEmail(email).orElse(null);
        String clientName = client != null ? client.getFirstName() + " " + client.getLastName() : email;

        Long accountId = Long.valueOf(String.valueOf(body.get("accountId")));
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Racun nije pronadjen"));

        // P1-authz-idor-1 (R1 114): IDOR — klijent je mogao da podnese zahtev za karticu
        // nad TUDJIM racunom prosledivanjem proizvoljnog accountId (account se ucitavao
        // bez ikakve provere vlasnistva). Klijent sme samo nad sopstvenim racunom; ako
        // pozivalac nije klijent (employee/admin agentic put), preskace se (oni legitimno
        // obradjuju tudje racune). IllegalState→403 (CardExceptionHandler).
        if (client != null) {
            boolean ownsAccount = account.getClient() != null
                    && account.getClient().getId().equals(client.getId());
            if (!ownsAccount) {
                throw new IllegalStateException("Racun ne pripada ulogovanom klijentu.");
            }
        }

        // ACCEPTED-DEVIATION (user-directed 03.06): nekadasnji C2 Sc28 email-kod gate
        // za zahtev kartice je UKLONJEN. Zahtev se podnosi direktno (PENDING), bez
        // verifikacionog koda. OTP/verifikacija vazi iskljucivo za placanja i transfere.

        BigDecimal limit = body.get("cardLimit") != null ? new BigDecimal(String.valueOf(body.get("cardLimit"))) : BigDecimal.valueOf(100000);

        CardType requestedCardType = CardType.VISA;
        if (body.get("cardType") != null) {
            try {
                requestedCardType = CardType.valueOf(String.valueOf(body.get("cardType")));
            } catch (IllegalArgumentException ignored) {
                // default to VISA if invalid value
            }
        }

        // Kategorija placanja (Debit/Credit/Internet prepaid) — odvojeno od brenda
        CardCategory requestedCategory = CardCategory.DEBIT;
        if (body.get("cardCategory") != null) {
            try {
                requestedCategory = CardCategory.valueOf(String.valueOf(body.get("cardCategory")));
            } catch (IllegalArgumentException ignored) {
                // default DEBIT
            }
        }
        BigDecimal creditLimitForCard = BigDecimal.ZERO;
        if (requestedCategory == CardCategory.CREDIT && body.get("creditLimit") != null) {
            try {
                creditLimitForCard = new BigDecimal(String.valueOf(body.get("creditLimit")));
            } catch (NumberFormatException ignored) {
                // default 0
            }
        }

        CardRequest req = CardRequest.builder()
                .account(account)
                .cardLimit(limit)
                .cardType(requestedCardType)
                .cardCategory(requestedCategory)
                .creditLimit(creditLimitForCard)
                .clientEmail(email)
                .clientName(clientName)
                .status("PENDING")
                .build();
        req = cardRequestRepository.save(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", req.getId(), "accountId", accountId, "status", "PENDING",
                "clientName", clientName, "createdAt", req.getCreatedAt().toString()));
    }

    @GetMapping("/requests/my")
    public ResponseEntity<Page<Map<String, Object>>> getMyCardRequests(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int limit) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        var pageable = PageRequest.of(page, limit, Sort.by("createdAt").descending());
        return ResponseEntity.ok(cardRequestRepository.findByClientEmail(email, pageable).map(this::toCardRequestMap));
    }

    @GetMapping("/requests")
    public ResponseEntity<Page<Map<String, Object>>> getAllCardRequests(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int limit) {
        var pageable = PageRequest.of(page, limit, Sort.by("createdAt").descending());
        if (status != null && !status.isBlank()) {
            return ResponseEntity.ok(cardRequestRepository.findByStatus(status, pageable).map(this::toCardRequestMap));
        }
        return ResponseEntity.ok(cardRequestRepository.findAll(pageable).map(this::toCardRequestMap));
    }

    @PatchMapping("/requests/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> approveCardRequest(@PathVariable Long id) {
        CardRequest req = cardRequestRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Zahtev nije pronadjen"));
        if (!"PENDING".equals(req.getStatus())) throw new IllegalStateException("Zahtev je vec obradjen");

        Account account = req.getAccount();
        Client owner = account.getClient();
        if (owner == null) throw new IllegalStateException("Racun nema vlasnika");
        CardType cardType = req.getCardType() != null ? req.getCardType() : CardType.VISA;
        CardCategory cardCategory = req.getCardCategory() != null ? req.getCardCategory() : CardCategory.DEBIT;
        BigDecimal creditLimit = req.getCreditLimit() != null ? req.getCreditLimit() : BigDecimal.ZERO;
        cardService.createCardForAccount(account.getId(), owner.getId(), req.getCardLimit(), cardType,
                cardCategory, creditLimit);

        String employeeEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        req.setStatus("APPROVED");
        req.setProcessedAt(LocalDateTime.now());
        req.setProcessedBy(employeeEmail);
        cardRequestRepository.save(req);
        return ResponseEntity.ok(toCardRequestMap(req));
    }

    @PatchMapping("/requests/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> rejectCardRequest(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        CardRequest req = cardRequestRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Zahtev nije pronadjen"));
        if (!"PENDING".equals(req.getStatus())) throw new IllegalStateException("Zahtev je vec obradjen");

        String employeeEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        req.setStatus("REJECTED");
        req.setRejectionReason(body != null ? body.getOrDefault("reason", null) : null);
        req.setProcessedAt(LocalDateTime.now());
        req.setProcessedBy(employeeEmail);
        cardRequestRepository.save(req);
        return ResponseEntity.ok(toCardRequestMap(req));
    }

    private Map<String, Object> toCardRequestMap(CardRequest req) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", req.getId());
        // Racun — pun detalj (broj, tip, valuta, balans) da zaposleni odmah vidi za
        // koji racun ide zahtev bez ekstra API poziva
        Account account = req.getAccount();
        map.put("accountId", account.getId());
        map.put("accountNumber", account.getAccountNumber());
        if (account.getAccountType() != null) {
            map.put("accountType", account.getAccountType().name());
        }
        if (account.getCurrency() != null) {
            map.put("accountCurrency", account.getCurrency().getCode());
        }
        map.put("accountBalance", account.getBalance());
        // Brend + kategorija + provider info
        map.put("cardLimit", req.getCardLimit());
        map.put("cardType", req.getCardType() != null ? req.getCardType().name() : null);
        map.put("cardCategory", req.getCardCategory() != null ? req.getCardCategory().name() : "DEBIT");
        if (req.getCreditLimit() != null && req.getCreditLimit().compareTo(BigDecimal.ZERO) > 0) {
            map.put("creditLimit", req.getCreditLimit());
        }
        // Klijent
        map.put("clientEmail", req.getClientEmail());
        map.put("clientName", req.getClientName());
        // Status / timestamps
        map.put("status", req.getStatus());
        map.put("createdAt", req.getCreatedAt().toString());
        if (req.getProcessedAt() != null) {
            map.put("processedAt", req.getProcessedAt().toString());
        }
        if (req.getProcessedBy() != null) {
            map.put("processedBy", req.getProcessedBy());
        }
        if (req.getRejectionReason() != null) {
            map.put("rejectionReason", req.getRejectionReason());
        }
        return map;
    }
}
