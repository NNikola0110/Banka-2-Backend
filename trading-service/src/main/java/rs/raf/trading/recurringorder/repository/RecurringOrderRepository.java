package rs.raf.trading.recurringorder.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rs.raf.trading.recurringorder.model.RecurringOrder;

// ============================================================
// TODO [B8 - Trajni nalozi (DCA / RecurringOrder) | Nosilac: Nikola Djurovic]
//
// JPA repozitorijum za trajne naloge.
//
// IMPLEMENTIRATI — dodati sledece metode:
//   - List<RecurringOrder> findByActiveTrue()
//       -> koristi ga scheduler da dobije sve aktivne naloge za tek izvrsavanje;
//          nije potrebna dodatna JPQL anotacija, Spring Data derivira upit automatski
//   - List<RecurringOrder> findByOwnerIdAndOwnerTypeOrderByCreatedAtDesc(Long ownerId, String ownerType)
//       -> vraca sve naloge jednog vlasnika (klijenta ili zaposlenog) sortirane
//          od najnovijeg ka najstarijem; koristi RecurringOrderService.listMy()
//   - @Query JPQL metoda findDue(LocalDateTime now) koja vraca sve aktivne naloge
//     ciji je nextRun <= :now; efikasnije od iteriranja svih aktivnih u scheduleru
//     kada baza ima veliki broj zapisa:
//         @Query("SELECT r FROM RecurringOrder r WHERE r.active = true AND r.nextRun <= :now")
//         List<RecurringOrder> findDue(@Param("now") LocalDateTime now);
//
// Konvencija: pratiti paket `savings` kao sablon.
// Spec: Zadaci_Backend.pdf, zadatak B8.
// ============================================================
public interface RecurringOrderRepository extends JpaRepository<RecurringOrder, Long> {
}
