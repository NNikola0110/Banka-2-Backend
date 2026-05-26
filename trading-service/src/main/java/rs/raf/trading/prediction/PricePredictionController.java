package rs.raf.trading.prediction;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * [W3-T2] Read-only REST endpoint za ML price predikcije.
 *
 * <p>Mountan na {@code /listings/{symbol}/prediction} (sa simbolom u path-u,
 * ne id-em, jer FE radi sa ticker-om na strani {@code ListingDetailPage}-a).
 * Svaki authenticated korisnik moze da cita — predikcija nije osetljiva
 * informacija (CLIENT moze da je vidi, kao i ostatak listing detalja).
 *
 * <p>Security mapping je u {@code TradingSecurityConfig}
 * (matcher {@code /listings/&#42;/prediction}). Nije ispod {@code /admin/}
 * prefiksa namerno — FE poziva kao deo listing detail-a.
 */
@RestController
@RequiredArgsConstructor
public class PricePredictionController {

    private final PricePredictionRepository repository;

    @GetMapping("/listings/{symbol}/prediction")
    public ResponseEntity<PricePredictionDto> getLatest(@PathVariable("symbol") String symbol) {
        return repository
                .findFirstBySymbolOrderByComputedAtDescPredictionDateDesc(symbol)
                .map(this::toDto)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Nema predikcije za simbol: " + symbol));
    }

    private PricePredictionDto toDto(PricePredictionEntity e) {
        return new PricePredictionDto(
                e.getId(),
                e.getSymbol(),
                e.getPredictionDate(),
                e.getPredictedClose(),
                e.getLowerBound(),
                e.getUpperBound(),
                e.getModelVersion(),
                e.getComputedAt()
        );
    }
}
