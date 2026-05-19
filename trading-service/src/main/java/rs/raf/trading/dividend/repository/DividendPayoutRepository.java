package rs.raf.trading.dividend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rs.raf.trading.dividend.model.DividendPayout;

// ============================================================
// TODO [B9 - Isplata dividendi na akcije | Nosilac: Djordje Zlatanovic]
//
// JPA repozitorijum za DividendPayout entitet.
//
// IMPLEMENTIRATI (custom metode koje treba dodati u interfejs):
//   - List<DividendPayout> findByOwnerIdAndOwnerTypeOrderByPaymentDateDesc(Long ownerId, String ownerType)
//       — istorija dividendi po korisniku (CLIENT ili EMPLOYEE), za GET /dividends/my endpoint
//   - List<DividendPayout> findByStockListingIdAndPaymentDate(Long stockListingId, LocalDate paymentDate)
//       — provera idempotentnosti: da li je za dati listing i kvartal vec isplacena dividenda
//         (koristiti u DividendService pre kreiranja novog PayoutRecorda kako bi scheduler bio
//         siguran da ne isplati duplo u slucaju restarta)
//   - List<DividendPayout> findByOwnerIdAndOwnerTypeAndStockListingId(Long ownerId, String ownerType, Long stockListingId)
//       — istorija dividendi po vlasniku i po hartiji, za GET /dividends/by-position/{portfolioId} endpoint
//   - Page<DividendPayout> findAllByOrderByPaymentDateDesc(Pageable pageable)
//       — admin pregled svih isplata (paginiran), za GET /admin/dividends endpoint
//   - @Query: findByPaymentDateBetween(LocalDate from, LocalDate to)
//       — za filtrirani admin pregled po datumskom opsegu kvartala
//
// Konvencija: pratiti paket `savings` kao sablon.
// Spec: Zadaci_Backend.pdf, zadatak B9.
// ============================================================

public interface DividendPayoutRepository extends JpaRepository<DividendPayout, Long> {
}
