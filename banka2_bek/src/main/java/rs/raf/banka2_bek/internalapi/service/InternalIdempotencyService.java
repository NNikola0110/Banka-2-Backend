package rs.raf.banka2_bek.internalapi.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

    /**
     * <b>N4 (P0-T2):</b> autoritativan read-only upit da li je idempotency kljuc VEC
     * KONZUMIRAN — tj. da li je operacija sa tim kljucem zaista izvrsena i njen rezultat
     * upisan u {@code internal_requests} dedup store. NE izvrsava nikakvu operaciju (cisto
     * citanje), pa je bezbedan za OTC SAGA recovery koji treba da utvrdi da li je F3 credit
     * proslo pre pada koordinatora (bez ponavljanja efekta). {@code null}/prazan kljuc → false.
     */
    @Transactional(readOnly = true)
    public boolean isConsumed(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return false;
        }
        return repository.findByIdempotencyKey(idempotencyKey).isPresent();
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

    /**
     * <b>P2-notif-reliability-2 (R1 383): ATOMICAN insert-or-skip guard.</b>
     *
     * <p>Stari obrazac "check-then-act" ({@code findCached(key)} → ako prazno →
     * izvrsi operaciju → {@code store(key)}) NIJE atomican: dve paralelne retry
     * dostave istog kljuca obe prodju {@code findCached} (jer jos nijedna nije
     * upisala), obe izvrse poslovnu operaciju (npr. {@code save(notification)}),
     * pa tek na {@code store} jedna padne na UNIQUE — ali je DUPLIKAT VEC NASTAO.
     *
     * <p>Ovde rezervisemo kljuc PRE poslovne operacije, oslanjajuci se na DB-level
     * {@code UNIQUE(idempotency_key)} ({@link InternalRequest}). {@code saveAndFlush}
     * forsira INSERT odmah → drugi paralelni pozivalac dobija
     * {@link DataIntegrityViolationException} (unique violation).
     *
     * <p><b>VAZNO:</b> izuzetak se NE hvata ovde — pusta se da propagira IZVAN
     * {@code REQUIRES_NEW} transakcije da bi Spring rollback-ovao SAMO ovu
     * (rezervacionu) tx, ostavljajuci spoljnu (poslovnu) tx cistom. Hvatanje
     * persistence izuzetka unutar iste tx-e ostavlja tu tx u rollback-only stanju
     * (commit bi onda pukao 500). Stoga {@code reserveOrThrow} baca, a CALLER
     * ({@code InternalNotificationsController}) hvata izuzetak (iz ciste outer tx)
     * i tretira ga kao "vec rezervisano → duplikat".
     *
     * @throws DataIntegrityViolationException ako je kljuc vec rezervisan
     *         (unique violation) — pozivalac treba da to tretira kao duplikat
     * @throws ConcurrencyFailureException ako dva INSERT-a istog kljuca trce
     *         (lock timeout, npr. H2 MVCC) — takodje duplikat
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserveOrThrow(String idempotencyKey, String endpoint, int httpStatus, String responseBody) {
        InternalRequest req = new InternalRequest();
        req.setIdempotencyKey(idempotencyKey);
        req.setEndpoint(endpoint);
        req.setHttpStatus(httpStatus);
        req.setResponseBody(responseBody);
        repository.saveAndFlush(req);
    }

}
