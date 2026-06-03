package rs.raf.trading.order.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    /**
     * Pessimistic write lock — koristi se u approve/decline flow-u da spreci
     * race izmedju supervizorskih akcija i scheduler fill-ova.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    List<Order> findByStatusAndIsDoneFalse(OrderStatus status);

    List<Order> findByUserIdAndUserRole(Long userId, String userRole);
    @Query("SELECT o FROM Order o WHERE o.isDone = false " +
           "AND o.status IN (rs.raf.trading.order.model.OrderStatus.PENDING, " +
           "rs.raf.trading.order.model.OrderStatus.APPROVED)")
    List<Order> findActiveNonDone();

    List<Order> findByIsDoneTrue();
}
