package rs.raf.banka2_bek.internalapi.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import rs.raf.banka2_bek.internalapi.model.InternalRequest;
import rs.raf.banka2_bek.internalapi.repository.InternalRequestRepository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P2-notif-reliability-2 (R1 383): {@link InternalIdempotencyService#reserveOrThrow}
 * atomican insert-or-throw guard. {@code reserveOrThrow} je REQUIRES_NEW i baca
 * izuzetak (CALLER ga hvata) — tako se izbegava rollback-only outer tx.
 */
@ExtendWith(MockitoExtension.class)
class InternalIdempotencyServiceTest {

    @Mock
    private InternalRequestRepository repository;

    @InjectMocks
    private InternalIdempotencyService service;

    @Test
    void reserveOrThrow_freshKey_inserts() {
        when(repository.saveAndFlush(any(InternalRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() ->
                service.reserveOrThrow("key-1", "/internal/notifications", 201, ""))
                .doesNotThrowAnyException();
    }

    @Test
    void reserveOrThrow_duplicateKey_propagatesUniqueViolation() {
        // Drugi pozivalac → unique(idempotency_key) prekrsen → izuzetak PROPAGIRA
        // (caller ga hvata kao duplikat). NE hvata se ovde da outer tx ostane cista.
        when(repository.saveAndFlush(any(InternalRequest.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint idempotency_key"));

        assertThatThrownBy(() ->
                service.reserveOrThrow("key-dup", "/internal/notifications", 201, ""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
