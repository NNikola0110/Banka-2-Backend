package rs.raf.trading.berza.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import rs.raf.trading.berza.model.Exchange;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExchangeRepository extends JpaRepository<Exchange, Long> {

    /**
     * Pronalazi berzu po skracenici (npr. "NYSE", "BELEX").
     */
    Optional<Exchange> findByAcronym(String acronym);

    /**
     * Vraca sve aktivne berze.
     */
    List<Exchange> findByActiveTrue();
}
