package rs.raf.trading.order.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.dto.OrderDto;
import rs.raf.trading.order.service.OrderService;

import java.time.LocalDate;
import java.util.Map;

/**
 * Controller za kreiranje i upravljanje orderima.
 * SecurityConfig je vec konfigurisan za ove rute.
 *
 * <p><b>ACCEPTED-DEVIATION (user-directed 03.06):</b> OTP/email-verifikacija je
 * UKLONJENA sa kreiranja ordera (berza/trading) po izricitoj korisnickoj UX
 * odluci — OTP ostaje ISKLJUCIVO na money-out flow-ovima (placanja + transferi).
 * Kreiranje BUY/SELL naloga sada ide direktno (uz uobicajene trade-permisija +
 * funds/holdings provere u {@link OrderService#createOrder}), bez verifikacionog
 * koda. {@code dto.otpCode} je zadrzan kao opcioni/ignorisan back-compat field
 * (FE sme da ga salje, BE ga ne gleda).
 */
@Tag(name = "Orders", description = "Kreiranje i upravljanje nalozima za trgovinu hartijama od vrednosti")
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * POST /orders - Kreiranje novog ordera (BUY ili SELL)
     * Pristup: aktuari i klijenti sa permisijom za trgovinu.
     *
     * <p>ACCEPTED-DEVIATION (user-directed 03.06): bez OTP gate-a — nalog se kreira
     * direktno. Autorizacija (autentifikacija + trade-permisija) ostaje u
     * SecurityConfig-u + {@code OrderServiceImpl.ensureTradingAccess}; novcane /
     * holdings provere ostaju netaknute u servisu.
     */
    @PostMapping
    public ResponseEntity<?> createOrder(@Valid @RequestBody CreateOrderDto dto) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth != null ? auth.getName() : null;
        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Neautorizovan pristup"));
        }

        OrderDto response = orderService.createOrder(dto);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /orders - Pregled svih ordera (supervizor portal)
     * Filtriranje po statusu: ALL, PENDING, APPROVED, DECLINED, DONE.
     *
     * <p>BE-ORD-03: {@code excludeFund=true} (default) iskljucuje FUND ordere
     * iz approval view-a. FUND ordere supervizori NE odobravaju ovde — fund
     * admin view ih nudi posebno (excludeFund=false) jer su to fund-management
     * akcije pokrenute od strane manager-a fonda.
     */
    @GetMapping
    public ResponseEntity<Page<OrderDto>> getAllOrders(
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "true") boolean excludeFund) {
        return ResponseEntity.ok(orderService.getAllOrders(status, page, size, excludeFund));
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
