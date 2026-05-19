package rs.raf.trading.internalapi.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rs.raf.trading.internalapi.model.InternalRequest;

import java.util.Optional;

public interface InternalRequestRepository extends JpaRepository<InternalRequest, Long> {
    Optional<InternalRequest> findByIdempotencyKey(String idempotencyKey);
}
