package rs.raf.banka2_bek.fraud;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * [W3-T2] Servis za pregled i reviziju Spark fraud detection alerta.
 *
 * <p>Read path: {@code findAlerts} (paged, filteri po {@code since},
 * {@code minRisk}, {@code onlyPending}).<br>
 * Write path: {@code reviewAlert} — postavlja {@code review_status},
 * {@code reviewed_by} i {@code reviewed_at}; idempotentno (re-review se
 * dozvoljava, npr. supervizor moze da podigne "false_positive" → "confirmed"
 * posle dodatnog dokaza).
 */
@Service
@RequiredArgsConstructor
public class FraudAlertService {

    private final TransactionAnomalyRepository repository;

    @Transactional(readOnly = true)
    public FraudAlertPageDto findAlerts(LocalDateTime since, BigDecimal minRisk,
                                        boolean onlyPending, Pageable pageable) {
        Page<FraudAlertDto> page = repository.findAlerts(since, minRisk, onlyPending, pageable)
                .map(this::toDto);
        return new FraudAlertPageDto(
                page.getContent(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize()
        );
    }

    @Transactional
    public FraudAlertDto reviewAlert(Long id, ReviewFraudAlertDto request) {
        TransactionAnomalyEntity alert = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Fraud alert ne postoji: " + id));

        String reviewer = currentSupervisorEmail();
        // Note se "smesta" u reviewedBy kao "email | note" da izbegnemo migraciju
        // schema-a (W2-T3 db-init je upravo deploy-ovan). FE moze da split-uje na " | ".
        String reviewerWithNote = (request.note() != null && !request.note().isBlank())
                ? reviewer + " | " + request.note().trim()
                : reviewer;

        alert.setReviewStatus(request.status());
        alert.setReviewedBy(reviewerWithNote);
        alert.setReviewedAt(LocalDateTime.now());

        return toDto(repository.save(alert));
    }

    private String currentSupervisorEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Nedostaje autentifikacioni kontekst za reviziju");
        }
        return auth.getName();
    }

    private FraudAlertDto toDto(TransactionAnomalyEntity e) {
        return new FraudAlertDto(
                e.getId(),
                e.getTransactionId(),
                e.getRiskScore(),
                e.getFeatures(),
                e.getModelVersion(),
                e.getComputedAt(),
                e.getReviewedBy(),
                e.getReviewStatus(),
                e.getReviewedAt()
        );
    }
}
