package rs.raf.banka2_bek.fraud;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [W3-T2] Unit testovi za {@link FraudAlertService}.
 *
 * <p>{@code currentSupervisorEmail()} cita iz {@link SecurityContextHolder},
 * pa svaki test seta/cisti context za izolaciju.
 */
@ExtendWith(MockitoExtension.class)
class FraudAlertServiceTest {

    @Mock
    private TransactionAnomalyRepository repository;

    @InjectMocks
    private FraudAlertService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private TransactionAnomalyEntity entity(Long id) {
        return TransactionAnomalyEntity.builder()
                .id(id)
                .transactionId(9000L + id)
                .riskScore(new BigDecimal("0.85"))
                .features("{\"amount_zscore\":3.1}")
                .modelVersion("iforest_v1")
                .computedAt(LocalDateTime.of(2026, 5, 26, 4, 0))
                .build();
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    @Test
    @DisplayName("findAlerts delegira filtere repozitorijumu i mapira DTO")
    void findAlerts_delegatesFiltersAndMaps() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<TransactionAnomalyEntity> page = new PageImpl<>(List.of(entity(1L), entity(2L)));
        when(repository.findAlerts(any(), any(), eq(true), eq(pageable))).thenReturn(page);

        FraudAlertPageDto result = service.findAlerts(null, null, true, pageable);

        assertThat(result.content()).hasSize(2);
        assertThat(result.content().get(0).riskScore()).isEqualByComparingTo("0.85");
        assertThat(result.content().get(0).features()).isEqualTo("{\"amount_zscore\":3.1}");
        assertThat(result.totalElements()).isEqualTo(2L);
    }

    @Test
    @DisplayName("reviewAlert nepostojeci id -> 404")
    void review_notFound_throws404() {
        authenticateAs("supervisor@banka.rs");
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reviewAlert(99L,
                new ReviewFraudAlertDto("confirmed", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("reviewAlert postavlja status + reviewer email")
    void review_setsStatusAndReviewer() {
        authenticateAs("supervisor@banka.rs");
        TransactionAnomalyEntity alert = entity(42L);
        when(repository.findById(42L)).thenReturn(Optional.of(alert));
        when(repository.save(any(TransactionAnomalyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        FraudAlertDto result = service.reviewAlert(42L,
                new ReviewFraudAlertDto("confirmed", null));

        ArgumentCaptor<TransactionAnomalyEntity> captor =
                ArgumentCaptor.forClass(TransactionAnomalyEntity.class);
        verify(repository).save(captor.capture());

        TransactionAnomalyEntity saved = captor.getValue();
        assertThat(saved.getReviewStatus()).isEqualTo("confirmed");
        assertThat(saved.getReviewedBy()).isEqualTo("supervisor@banka.rs");
        assertThat(saved.getReviewedAt()).isNotNull();

        assertThat(result.reviewStatus()).isEqualTo("confirmed");
    }

    @Test
    @DisplayName("reviewAlert sa note -> dopisuje '| note' u reviewedBy polju")
    void review_withNote_appendsNoteToReviewer() {
        authenticateAs("supervisor@banka.rs");
        TransactionAnomalyEntity alert = entity(42L);
        when(repository.findById(42L)).thenReturn(Optional.of(alert));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reviewAlert(42L,
                new ReviewFraudAlertDto("false_positive", "lazno pozitivan — interna kartica"));

        ArgumentCaptor<TransactionAnomalyEntity> captor =
                ArgumentCaptor.forClass(TransactionAnomalyEntity.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getReviewedBy())
                .isEqualTo("supervisor@banka.rs | lazno pozitivan — interna kartica");
    }

    @Test
    @DisplayName("reviewAlert bez autentifikacije -> 401")
    void review_noAuth_throws401() {
        // No setupAuth — SecurityContextHolder je clean.
        when(repository.findById(1L)).thenReturn(Optional.of(entity(1L)));

        assertThatThrownBy(() -> service.reviewAlert(1L,
                new ReviewFraudAlertDto("confirmed", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
    }
}
