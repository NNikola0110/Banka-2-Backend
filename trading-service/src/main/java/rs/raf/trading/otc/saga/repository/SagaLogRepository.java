package rs.raf.trading.otc.saga.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import rs.raf.trading.otc.saga.model.SagaLog;
import rs.raf.trading.otc.saga.model.SagaStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface SagaLogRepository extends JpaRepository<SagaLog, Long> {
    Optional<SagaLog> findBySagaId(String sagaId);
    List<SagaLog> findByContractId(Long contractId);
    List<SagaLog> findByStatusIn(List<SagaStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SagaLog s WHERE s.sagaId = :sagaId")
    Optional<SagaLog> findBySagaIdForUpdate(@Param("sagaId") String sagaId);
}
