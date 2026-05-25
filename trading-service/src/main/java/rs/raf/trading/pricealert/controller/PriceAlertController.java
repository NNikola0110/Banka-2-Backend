package rs.raf.trading.pricealert.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rs.raf.trading.pricealert.dto.CreatePriceAlertDto;
import rs.raf.trading.pricealert.dto.PriceAlertDto;
import rs.raf.trading.pricealert.service.PriceAlertService;

import java.util.List;

/**
 * [B5 - Cenovni alarmi] REST endpoint-i za cenovne alarme.
 *
 * <p>Sve rute autentifikovane (JWT). Spring Security mapiranje
 * {@code /price-alerts/**} se konfigurise u {@code TradingSecurityConfig}.
 */
@RestController
@RequestMapping("/price-alerts")
@RequiredArgsConstructor
public class PriceAlertController {

    private final PriceAlertService priceAlertService;

    @PostMapping
    public ResponseEntity<PriceAlertDto> create(@Valid @RequestBody CreatePriceAlertDto dto) {
        PriceAlertDto created = priceAlertService.createAlert(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/my")
    public ResponseEntity<List<PriceAlertDto>> listMy(
            @RequestParam(value = "active", required = false) Boolean active) {
        return ResponseEntity.ok(priceAlertService.listMyAlerts(active));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        priceAlertService.deleteAlert(id);
        return ResponseEntity.noContent().build();
    }
}
