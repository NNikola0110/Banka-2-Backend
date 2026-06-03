package rs.raf.banka2_bek.employee.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import rs.raf.banka2_bek.auth.repository.PasswordResetTokenRepository;
import rs.raf.banka2_bek.employee.model.ActivationToken;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.ActivationTokenRepository;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.notification.NotificationPublisher;
import rs.raf.banka2_bek.IntegrationTestCleanup;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpStatus.OK;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EmployeeAuthControllerIntegrationTest {

        private final RestTemplate restTemplate = new RestTemplate();

    @MockitoBean
    private NotificationPublisher notificationPublisher;

        @Value("${local.server.port}")
        private int port;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ActivationTokenRepository activationTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

        @Autowired
        private PasswordResetTokenRepository passwordResetTokenRepository;

        @Autowired
        private DataSource dataSource;

        @BeforeEach
        void cleanDatabase() {
                IntegrationTestCleanup.truncateAllTables(dataSource);
        }

        private String url(String path) {
                return "http://localhost:" + port + path;
        }

    @Test
    void activateAccountSetsPasswordAndMarksTokenUsed() throws Exception {
        Employee employee = Employee.builder()
                .firstName("Ana")
                .lastName("Test")
                .email("activate@test.com")
                .phone("+38160111222")
                .address("Test")
                .username("ana")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender("F")
                .password(passwordEncoder.encode("Temp12" + "salt"))
                .saltPassword("salt")
                .position("QA")
                .department("IT")
                .active(false)
                .permissions(Set.of("VIEW_STOCKS"))
                .build();
        employeeRepository.save(employee);

        ActivationToken token = ActivationToken.builder()
                .token("activate-token")
                .employee(employee)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .used(false)
                .invalidated(false)
                .build();
        activationTokenRepository.save(token);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String payload = """
                {
                  "token": "activate-token",
                  "password": "NewPass12"
                }
                """;
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/auth-employee/activate"),
                new HttpEntity<>(payload, headers),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(OK);

        Employee updated = employeeRepository.findByEmail("activate@test.com").orElseThrow();
        assertThat(updated.getActive()).isTrue();
        assertThat(passwordEncoder.matches("NewPass12" + updated.getSaltPassword(), updated.getPassword())).isTrue();

        ActivationToken updatedToken = activationTokenRepository.findByToken("activate-token").orElseThrow();
        assertThat(updatedToken.isUsed()).isTrue();
        assertThat(updatedToken.isInvalidated()).isTrue();
        assertThat(updatedToken.getUsedAt()).isNotNull();
    }

    /**
     * Spec Celina 1 Sc 9: POST /auth-employee/resend-activation za neaktivnog
     * zaposlenog sa isteklim tokenom invalidira stari token, perzistuje svez i
     * salje nov aktivacioni email — uz generic 200 odgovor.
     */
    @Test
    void resendActivationInvalidatesOldTokenAndSendsNewMail() {
        Employee employee = Employee.builder()
                .firstName("Bojan")
                .lastName("Resend")
                .email("resend@test.com")
                .phone("+38160333444")
                .address("Test")
                .username("bojan")
                .dateOfBirth(LocalDate.of(1991, 2, 2))
                .gender("M")
                .password(passwordEncoder.encode("Temp12" + "salt"))
                .saltPassword("salt")
                .position("QA")
                .department("IT")
                .active(false)
                .permissions(Set.of("VIEW_STOCKS"))
                .build();
        employeeRepository.save(employee);

        ActivationToken expiredToken = ActivationToken.builder()
                .token("expired-resend-token")
                .employee(employee)
                .expiresAt(LocalDateTime.now().minusHours(1))
                .used(false)
                .invalidated(false)
                .build();
        activationTokenRepository.save(expiredToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String payload = """
                { "token": "expired-resend-token" }
                """;
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/auth-employee/resend-activation"),
                new HttpEntity<>(payload, headers),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(OK);

        // Stari token je invalidovan.
        ActivationToken oldToken = activationTokenRepository.findByToken("expired-resend-token").orElseThrow();
        assertThat(oldToken.isInvalidated()).isTrue();

        // Nov, validan token postoji za istog zaposlenog (svez TTL ~24h).
        long activeForEmployee = activationTokenRepository.findAll().stream()
                .filter(t -> t.getEmployee() != null && t.getEmployee().getId().equals(employee.getId()))
                .filter(t -> !t.isInvalidated() && !t.isUsed())
                .filter(t -> t.getExpiresAt().isAfter(LocalDateTime.now().plusHours(23)))
                .count();
        assertThat(activeForEmployee).isEqualTo(1);

        // Nov aktivacioni email je publish-ovan.
        verify(notificationPublisher).sendActivationMail(eq("resend@test.com"), eq("Bojan"), anyString());
    }

    /**
     * Anti-enumeration: nepoznat token vraca isti generic 200 i ne salje email.
     */
    @Test
    void resendActivationUnknownTokenReturnsGenericOkNoLeak() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String payload = """
                { "token": "does-not-exist" }
                """;
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/auth-employee/resend-activation"),
                new HttpEntity<>(payload, headers),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(OK);

        verify(notificationPublisher, never()).sendActivationMail(anyString(), anyString(), anyString());
    }
}
