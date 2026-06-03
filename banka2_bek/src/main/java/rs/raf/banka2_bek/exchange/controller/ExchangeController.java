package rs.raf.banka2_bek.exchange.controller;

import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rs.raf.banka2_bek.exchange.ExchangeService;
import rs.raf.banka2_bek.exchange.dto.CalculateExchangeResponseDto;
import rs.raf.banka2_bek.exchange.dto.ExchangeRateDto;

import java.util.List;

@RestController
@Validated
public class ExchangeController {

    private final ExchangeService exchangeService;

    public ExchangeController(ExchangeService exchangeService) {
        this.exchangeService = exchangeService;
    }

    @GetMapping("/exchange-rates")
    public List<ExchangeRateDto> getExchangeRates() {
        return exchangeService.getAllRates();
    }

    @GetMapping("/exchange/calculate")
    public ResponseEntity<CalculateExchangeResponseDto> calculate(
            // [P2-input-validation-1 / R1 366] amount mora biti > 0 (negativan/0 je
            // ranije prolazio i racunao besmislenu konverziju).
            @RequestParam @Positive(message = "Iznos mora biti pozitivan") double amount,
            @RequestParam String toCurrency,
            @RequestParam(required = false, defaultValue = "RSD") String fromCurrency){

        CalculateExchangeResponseDto result = exchangeService.calculateCross(amount,fromCurrency, toCurrency);
        return ResponseEntity.ok(result);
    }

}