package rs.raf.trading.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import rs.raf.trading.client.BankaCoreClient;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-7 — {@code /audit/**} u trading-service mora biti zasticen kao u banka-core
 * ({@code GlobalSecurityConfig}): samo ADMIN i SUPERVISOR smeju da citaju audit log.
 *
 * <p>Pre fix-a {@link TradingSecurityConfig} nije imao matcher za {@code /audit/**},
 * pa je ruta padala na {@code .anyRequest().authenticated()} — bilo koji
 * autentifikovani korisnik (npr. CLIENT) je prolazio autorizaciju i citao log.
 *
 * <p>HTTP integracioni test (RANDOM_PORT, realan {@link TradingSecurityConfig}
 * filter chain) — mirror {@code OrderControllerIntegrationTest}. JWT se mintuje
 * lokalno deljenim test secret-om; {@link BankaCoreClient} je {@code @MockitoBean}
 * pa {@code TradingPermissionResolver} vraca praznu listu permisija — autoriteti
 * su tacno {@code ROLE_<role>} iz JWT {@code role} claim-a. Tako test izoluje
 * BAS pravilo autorizacije iz security konfiguracije.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuditEndpointSecurityTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    @Value("${local.server.port}")
    private int port;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

    private final RestTemplate restTemplate = createRestTemplate();
    private final SecretKey secretKey =
            Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));

    private static RestTemplate createRestTemplate() {
        RestTemplate rt = new RestTemplate(new JdkClientHttpRequestFactory());
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        return rt;
    }

    private String buildToken(String subject, String role) {
        return Jwts.builder()
                .subject(subject)
                .claim("role", role)
                .claim("active", true)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000L))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    private ResponseEntity<String> getAudit(String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(buildToken(role.toLowerCase() + "@test.com", role));
        return restTemplate.exchange(
                "http://localhost:" + port + "/audit",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    @Test
    @DisplayName("GET /audit — CLIENT dobija 403 (autorizacija ga odbija)")
    void client_isForbiddenOnAudit() {
        ResponseEntity<String> response = getAudit("CLIENT");

        assertThat(response.getStatusCode())
                .as("CLIENT ne sme da cita audit log — mora 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /audit — ADMIN prolazi autorizaciju (NIJE 403)")
    void admin_passesAuthorizationOnAudit() {
        ResponseEntity<String> response = getAudit("ADMIN");

        assertThat(response.getStatusCode().value())
                .as("ADMIN ne sme da dobije 403 — autorizacija mora da prodje (200/400 ok)")
                .isNotEqualTo(403);
    }
}
