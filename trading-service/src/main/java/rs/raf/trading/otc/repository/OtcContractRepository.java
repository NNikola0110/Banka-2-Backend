package rs.raf.trading.otc.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.otc.model.OtcContractStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface OtcContractRepository extends JpaRepository<OtcContract, Long> {

    /**
     * <b>P2-6:</b> ucitava ugovor sa pesimistickim WRITE lock-om radi serijalizacije
     * ACTIVE→EXERCISED tranzicije. Koristi ga {@code OtcExerciseSagaOrchestrator}
     * pre-saga gate da bi dva konkurentna {@code POST .../exercise} nad istim ACTIVE
     * ugovorom bila serijalizovana: drugi blokira dok prvi ne commit-uje, pa vidi
     * ugovor vec EXERCISED (status != ACTIVE → 409). Bez ovog lock-a oba prolaze
     * ACTIVE check i oba commit-uju istu rezervaciju u F3 (dvostruka naplata / lazni
     * C3 refund). Mirror obrazca {@code PortfolioRepository.findByIdForUpdate} i
     * {@code SagaLogRepository.findBySagaIdForUpdate}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM OtcContract c WHERE c.id = :id")
    Optional<OtcContract> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT c FROM OtcContract c WHERE " +
           "((c.buyerId = :userId AND c.buyerRole = :userRole) " +
           " OR (c.sellerId = :userId AND c.sellerRole = :userRole)) " +
           "ORDER BY c.createdAt DESC")
    List<OtcContract> findAllForUser(@Param("userId") Long userId,
                                     @Param("userRole") String userRole);

    @Query("SELECT c FROM OtcContract c WHERE " +
           "((c.buyerId = :userId AND c.buyerRole = :userRole) " +
           " OR (c.sellerId = :userId AND c.sellerRole = :userRole)) " +
           "AND c.status = :status ORDER BY c.createdAt DESC")
    List<OtcContract> findByUserAndStatus(@Param("userId") Long userId,
                                          @Param("userRole") String userRole,
                                          @Param("status") OtcContractStatus status);

    /**
     * Ukupna kolicina akcija na aktivnim (nekoriscenim i nesistekllim) ugovorima
     * za jednog prodavca i jedan listing. Koristi se za rezervaciju publicQuantity.
     */
    @Query("SELECT COALESCE(SUM(c.quantity), 0) FROM OtcContract c " +
           "WHERE c.sellerId = :sellerId AND c.sellerRole = :sellerRole " +
           "AND c.listing.id = :listingId " +
           "AND c.status = rs.raf.trading.otc.model.OtcContractStatus.ACTIVE")
    int sumActiveReservedByListing(@Param("sellerId") Long sellerId,
                                   @Param("sellerRole") String sellerRole,
                                   @Param("listingId") Long listingId);

    @Query("SELECT c FROM OtcContract c WHERE c.status = rs.raf.trading.otc.model.OtcContractStatus.ACTIVE " +
           "AND c.settlementDate < :today")
    List<OtcContract> findExpiredActive(@Param("today") LocalDate today);

    @Query("SELECT c FROM OtcContract c WHERE c.status = rs.raf.trading.otc.model.OtcContractStatus.ACTIVE " +
           "AND c.settlementDate = :targetDate")
    List<OtcContract> findActiveExpiringOn(@Param("targetDate") LocalDate targetDate);

    /**
     * <b>P2-tax-cost-basis-1 (R5 1901):</b> ucitava EXERCISED ugovore nad
     * STOCK listinzima za poreski obracun. Zamenjuje {@code findAll()} + in-memory
     * filter u {@code TaxService.calculateTaxForAllUsers} (pun table-scan koji raste
     * neograniceno jer EXERCISED ostaje zauvek). STOCK filter je u upitu jer je
     * intra-bank OTC iskljucivo akcijska kupoprodaja (Celina 4 §75). Ovi ugovori su
     * SVI intra-bank (kreira ih {@code OtcService.acceptOffer}); inter-bank OTC se
     * NE perzistuje u {@code otc_contracts} (vidi {@code TaxService} R1 431 napomenu).
     */
    @Query("SELECT c FROM OtcContract c WHERE c.status = rs.raf.trading.otc.model.OtcContractStatus.EXERCISED " +
           "AND c.listing.listingType = rs.raf.trading.stock.model.ListingType.STOCK")
    List<OtcContract> findExercisedStockContracts();
}
