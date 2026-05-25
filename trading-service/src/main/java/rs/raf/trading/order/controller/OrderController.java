package rs.raf.trading.order.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import rs.raf.banka2.contracts.internal.InternalOtpVerifyResponse;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.dto.OrderDto;
import rs.raf.trading.order.service.OrderService;

import java.time.LocalDate;
import java.util.Map;

/**
 * Controller za kreiranje i upravljanje orderima.
 * SecurityConfig je vec konfigurisan za ove rute.
 *
 * NAPOMENA (copy-first ekstrakcija, faza 2c): u monolitu je OTP verifikaciju
 * radio lokalni {@code OtpService}. U trading-service-u OTP zivi u banka-core
 * domenu, pa se verifikacija radi preko banka-core internog seam-a
 * ({@link BankaCoreClient#verifyOtp} — {@code POST /internal/otp/verify}).
 */
@Tag(name = "Orders", description = "Kreiranje i upravljanje nalozima za trgovinu hartijama od vrednosti")
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final BankaCoreClient bankaCoreClient;

    /**
     * POST /orders - Kreiranje novog ordera (BUY ili SELL)
     * Pristup: aktuari i klijenti sa permisijom za trgovinu.
     *
     * OTP se verifikuje preko banka-core internog seam-a. Ako kreiranje ordera
     * pukne, trgovinska transakcija radi rollback; banka-core OTP used=true
     * marking je u zasebnoj banka-core transakciji (verifikacija je idempotentna
     * za isti kod u kratkom prozoru).
     */
    @PostMapping
    @Transactional
    public ResponseEntity<?> createOrder(@Valid @RequestBody CreateOrderDto dto) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth != null ? auth.getName() : null;
        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Neautorizovan pristup"));
        }

        InternalOtpVerifyResponse otpResult = bankaCoreClient.verifyOtp(email, dto.getOtpCode());
        if (!otpResult.verified()) {
            String message = otpResult.blocked()
                    ? "Verifikacija blokirana — previse neuspesnih pokusaja"
                    : "Verifikacija neuspesna";
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("verified", otpResult.verified());
            body.put("blocked", otpResult.blocked());
            body.put("message", message);
            body.put("error", message);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }

        OrderDto response = orderService.createOrder(dto);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /orders - Pregled svih ordera (supervizor portal)
     * Filtriranje po statusu: ALL, PENDING, APPROVED, DECLINED, DONE
     */
    @GetMapping
    public ResponseEntity<Page<OrderDto>> getAllOrders(
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.getAllOrders(status, page, size));
    }

    /**
     * GET /orders/my - Moji orderi (za korisnika)
     */
    @GetMapping("/my")
    public ResponseEntity<Page<OrderDto>> getMyOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) String listingType) {
        return ResponseEntity.ok(orderService.getMyOrders(page, size, status, dateFrom, dateTo, listingType));
    }

    /**
     * GET /orders/{id} - Detalji jednog ordera
     */
    @GetMapping("/{id}")
    public ResponseEntity<OrderDto> getOrderById(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }

    /**
     * PATCH /orders/{id}/approve - Supervizor odobrava order
     */
    @PatchMapping("/{id}/approve")
    public ResponseEntity<OrderDto> approveOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.approveOrder(id));
    }

    /**
     * PATCH /orders/{id}/decline - Supervizor odbija order
     * <p>
     * Ako je prosledjen {@code ?quantity=X} i X < remainingPortions, order
     * ostaje APPROVED ali sa skracenim remainingPortions (parcijalni cancel,
     * spec: "otkazivanje celog ili dela Order-a koji još uvek nije ispunjen").
     * Inace se odbija ceo order kao i ranije.
     */
    @PatchMapping("/{id}/decline")
    public ResponseEntity<OrderDto> declineOrder(
            @PathVariable Long id,
            @RequestParam(required = false) Integer quantity) {
        if (quantity == null) {
            return ResponseEntity.ok(orderService.declineOrder(id));
        }
        return ResponseEntity.ok(orderService.cancelOrder(id, quantity));
    }
}
