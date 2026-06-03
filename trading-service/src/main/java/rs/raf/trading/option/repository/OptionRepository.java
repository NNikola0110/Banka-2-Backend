package rs.raf.trading.option.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import rs.raf.trading.option.model.Option;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * JPA repozitorijum za Option entitet.
 *
 * NAPOMENA: Upiti koji vracaju opcije za odredjenu akciju (stockListingId) ce se
 * najcesce koristiti na frontendu za prikaz "option chain" tabele.
 */
@Repository
public interface OptionRepository extends JpaRepository<Option, Long> {

    /**
     * Pronalazi sve opcije (CALL i PUT) za odredjenu akciju.
     *
     * @param listingId ID Listing entiteta (akcije)
     * @return lista svih opcija vezanih za tu akciju
     */
    List<Option> findByStockListingId(Long listingId);

    /**
     * [OT-896] Dohvata SVE opcije sa eager (JOIN FETCH) ucitanim
     * {@code stockListing}-om u jednom upitu. {@code stockListing} je
     * {@code @ManyToOne(fetch = LAZY)}, pa bi obican {@code findAll()} praćen
     * iteracijom {@code option.getStockListing().getPrice()} (kao u
     * {@code recalculatePrices}) okinuo N+1 (1 + N upita — jedan po opciji).
     * Join-fetch ucitava opcije + njihove osnovne akcije u JEDNOM SELECT-u.
     *
     * @return sve opcije sa inicijalizovanim {@code stockListing}-om
     */
    @Query("SELECT o FROM Option o JOIN FETCH o.stockListing")
    List<Option> findAllWithStockListing();

    /**
     * Pesimisticki write-lock dohvat opcije po ID-u — koristi se u
     * {@code exerciseOption} flow-u da spreci lost-update trku: dva paralelna
     * exercise-a bi inace procitala isti {@code openInterest}, dvaput ga
     * dekrementirala sa stale vrednosti i (preko idempotency replay-a) zaduzila
     * settlement samo jednom. Lock drzi red opcije do kraja exercise transakcije.
     *
     * <p>Mirror {@code OrderRepository.findByIdForUpdate} /
     * {@code PortfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate}.
     *
     * @param id ID Option entiteta
     * @return zakljucani Option red ili prazan Optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Option o WHERE o.id = :id")
    Optional<Option> findByIdForUpdate(@Param("id") Long id);

    /**
     * Pronalazi sve istekle opcije (settlement datum pre zadatog datuma).
     *
     * @param date referentni datum (obicno LocalDate.now())
     * @return lista isteklih opcija
     */
    List<Option> findBySettlementDateBefore(LocalDate date);

    /**
     * Brise sve istekle opcije iz baze.
     *
     * @param date referentni datum -- sve opcije sa settlementDate < date ce biti obrisane
     */
    @Modifying
    @Query("DELETE FROM Option o WHERE o.settlementDate < :date")
    void deleteBySettlementDateBefore(@Param("date") LocalDate date);

    /**
     * Proverava da li vec postoje opcije za datu akciju i settlement datum.
     *
     * @param listingId ID Listing entiteta
     * @param date      settlement datum
     * @return true ako vec postoje opcije za taj par (listing, datum)
     */
    boolean existsByStockListingIdAndSettlementDate(Long listingId, LocalDate date);
}
