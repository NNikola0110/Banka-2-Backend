package rs.raf.trading.recurringorder.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.trading.recurringorder.model.RecurringOrder;

import java.time.LocalDateTime;
import java.util.List;

// ============================================================
// [B8 - Trajni nalozi (DCA / RecurringOrder) | Nosilac: Nikola Djurovic] - DONE
//
// JPA repozitorijum za trajne naloge.
//
// Spec: Zadaci_Backend.pdf, zadatak B8.
// ============================================================
public interface RecurringOrderRepository extends JpaRepository<RecurringOrder, Long> {

    List<RecurringOrder> findByOwnerIdAndOwnerTypeOrderByCreatedAtDesc(Long ownerId, String ownerType);

    @Query("SELECT r FROM RecurringOrder r WHERE r.active = true AND r.nextRun <= :now")
    List<RecurringOrder> findDue(@Param("now") LocalDateTime now);
}
