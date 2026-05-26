package rs.raf.trading.prediction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * [W3-T2] Repozitorijum za {@link PricePredictionEntity}.
 *
 * <p>Default endpoint ({@code GET /listings/{symbol}/prediction}) vraca
 * NAJNOVIJU predikciju za simbol; sortiramo po
 * {@code computedAt DESC, predictionDate DESC} da hvatamo i slucaj kad isti
 * Spark run upise N+1 i N+2 predikcije, a klijentu interesuje sutrasnja
 * (najnovija "computedAt" + najraniji "predictionDate" nakon today).
 *
 * <p>Pošto Spring Data nema garanciju order-a u "FindFirst" sa multiple
 * sort-ima zbog naming convention dvosmislenosti, koristimo TWO sort
 * field-a u jednom metodu. To je standardna konvencija (najprostije pravilo
 * naming-a — "FindFirstByXOrderByYDescZDesc").
 */
@Repository
public interface PricePredictionRepository extends JpaRepository<PricePredictionEntity, Long> {

    /**
     * Najnoviji upis ZA dati simbol. ML job moze upisati vise predikcija
     * (npr. razlicit horizon ili rerun); klijentu se vraca poslednji racunarski
     * red sa najkasnijim {@code prediction_date}.
     */
    Optional<PricePredictionEntity> findFirstBySymbolOrderByComputedAtDescPredictionDateDesc(String symbol);
}
