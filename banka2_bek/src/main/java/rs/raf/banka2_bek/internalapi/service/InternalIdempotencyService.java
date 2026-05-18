package rs.raf.banka2_bek.internalapi.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.internalapi.model.InternalRequest;
import rs.raf.banka2_bek.internalapi.repository.InternalRequestRepository;

import java.util.Optional;

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
