package rs.raf.trading.internalapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.ReassignFundManagerRequest;
import rs.raf.banka2.contracts.internal.ReassignFundManagerResponse;
import rs.raf.trading.internalapi.model.InternalRequest;
import rs.raf.trading.investmentfund.service.InvestmentFundService;

import java.util.Optional;

/**
 * Jezgro internog fond seam-a ({@code /internal/funds/**}).
 *
 * <p>Faza 2f: posle cutover-a {@code investment_funds} tabela zivi samo u
 * trading_db. banka-core {@code employee} paket (kada admin oduzme SUPERVISOR
 * permisiju supervizoru) vise ne sme da radi in-process JPA bulk reassign nego
 * zove ovaj servis preko HTTP-a. {@code reassignFundManager} delegira na
 * {@link InvestmentFundService#reassignFundManager(Long, Long)} (bulk JPA
 * {@code update}) — verno reprodukuje monolitovu logiku.
 *
 * <p>Bulk reassign je idempotentan ({@code X-Idempotency-Key} + kesiranje;
 * mirror {@code InternalPortfolioService}): JPA {@code update} keyed na
 * {@code oldManagerEmployeeId} je apsolutan SET, pa drugi poziv sa istim kljucem
 * vraca kesiran broj iz prvog poziva (umesto 0 — kao da nijedan fond vise nema
 * starog menadzera). store + operacija su atomicni u jednoj {@code @Transactional}.
 */
@Service
public class InternalFundService {

    private static final Logger log = LoggerFactory.getLogger(InternalFundService.class);

    private final InvestmentFundService investmentFundService;
    private final InternalIdempotencyService idempotencyService;

    /**
     * Privatni {@code ObjectMapper} — koristi se SAMO za (de)serijalizaciju
     * idempotency response tela. {@link ReassignFundManagerResponse} je
     * jednostavan record bez {@code java.time} polja, pa plain mapper dovoljava.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InternalFundService(InvestmentFundService investmentFundService,
                               InternalIdempotencyService idempotencyService) {
        this.investmentFundService = investmentFundService;
        this.idempotencyService = idempotencyService;
    }

    /** Idempotent wrapper: bulk reassign + idempotency store u jednoj transakciji. */
    @Transactional
    public ReassignFundManagerResponse reassignFundManagerIdempotent(String idempotencyKey,
                                                                     ReassignFundManagerRequest req) {
        Optional<InternalRequest> cached = idempotencyService.findCached(idempotencyKey);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getResponseBody(), ReassignFundManagerResponse.class);
            } catch (Exception e) {
                log.warn("Idempotency deserialization failed for key {}: {}", idempotencyKey, e.getMessage());
            }
        }
        ReassignFundManagerResponse result = reassignFundManager(req);
        storeIdempotency(idempotencyKey, "/internal/funds/reassign-manager", result);
        return result;
    }

    /**
     * Bulk prebacivanje vlasnistva: svi fondovi kojima upravlja
     * {@code oldManagerEmployeeId} dobijaju {@code newManagerEmployeeId}.
     * Verno monolitovom {@code InvestmentFundService.reassignFundManager}
     * (delegira na {@link InvestmentFundService#reassignFundManager(Long, Long)}
     * koji radi JPA bulk {@code update} i no-op-uje kada su id-evi null/jednaki).
     */
    @Transactional
    public ReassignFundManagerResponse reassignFundManager(ReassignFundManagerRequest req) {
        int reassigned = investmentFundService.reassignFundManager(
                req.oldManagerEmployeeId(), req.newManagerEmployeeId());
        return new ReassignFundManagerResponse(reassigned);
    }

    private void storeIdempotency(String key, String endpoint, Object result) {
        String body;
        try {
            body = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            // Serijalizacija MORA uspeti: idempotency kes mora biti konzistentan sa
            // izvrsenom operacijom. Propagiramo (unchecked) da se cela @Transactional
            // operacija rollback-uje — bez divergencije commit-ovano stanje vs kes.
            throw new RuntimeException("Idempotency serijalizacija nije uspela za kljuc " + key, e);
        }
        idempotencyService.store(key, endpoint, 200, body);
    }
}
