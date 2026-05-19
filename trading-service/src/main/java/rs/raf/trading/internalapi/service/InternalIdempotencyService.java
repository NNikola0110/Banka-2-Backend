package rs.raf.trading.internalapi.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.internalapi.model.InternalRequest;
import rs.raf.trading.internalapi.repository.InternalRequestRepository;

import java.util.Optional;

/**
 * Idempotency store za trading-service interni API. Mirror banka-core
 * {@code internalapi.service.InternalIdempotencyService}.
 */
@Service
public class InternalIdempotencyService {

    private final InternalRequestRepository repository;

    public InternalIdempotencyService(InternalRequestRepository repository) {
        this.repository = repository;
    }

    /** Kesiran (httpStatus, responseBody) ako je kljuc vec obradjen. */
    @Transactional(readOnly = true)
    public Optional<InternalRequest> findCached(String idempotencyKey) {
        return repository.findByIdempotencyKey(idempotencyKey);
    }

    /** Snima rezultat u istoj transakciji kao poslovna operacija. */
    public void store(String idempotencyKey, String endpoint, int httpStatus, String responseBody) {
        InternalRequest req = new InternalRequest();
        req.setIdempotencyKey(idempotencyKey);
        req.setEndpoint(endpoint);
        req.setHttpStatus(httpStatus);
        req.setResponseBody(responseBody);
        repository.save(req);
    }
}
