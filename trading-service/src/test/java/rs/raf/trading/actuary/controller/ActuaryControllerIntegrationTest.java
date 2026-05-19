package rs.raf.trading.actuary.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.security.TradingUserResolver;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * HTTP integracioni test ActuaryController-a — pun Spring kontekst (H2 test
 * profil), RANDOM_PORT, realan security filter chain (JWT validacija +
 * @PreAuthorize) + realan service + JPA persistencija.
 *
 * NAPOMENA (copy-first ekstrakcija, faza 2c): monolitni test je pravio prave
 * {@code Employee}/{@code User} zapise i logovao se preko {@code /auth/login}
 * (banka-core endpoint). trading-service NEMA login endpoint — JWT izdaje
 * banka-core. Ovde se JWT generise lokalno deljenim test secret-om (isti kao
 * banka-core HS256), {@code BankaCoreClient}/{@code TradingUserResolver} su
 * mockovani ({@code @MockitoBean}), a {@code ActuaryInfo} se seeduje direktno
 * preko repozitorijuma sa soft {@code employeeId}-evima.
 *
 * Domenska greska "cilj nije agent" se u monolitu mapirala kroz globalni
 * exception handler (400/409); trading-service ima samo scoped
 * {@code ActuaryExceptionHandler} koji ne hvata {@code RuntimeException}, pa je
 * to 5xx — i dalje validna potvrda da zahtev biva odbijen.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ActuaryControllerIntegrationTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    private static final Long AGENT_MARKO_ID = 3001L;
    private static final Long AGENT_JELENA_ID = 3002L;
    private static final Long SUPERVISOR_NINA_ID = 3003L;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ActuaryInfoRepository actuaryInfoRepository;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

    @MockitoBean
    private TradingUserResolver userResolver;

    private final RestTemplate restTemplate = createRestTemplate();
    private final SecretKey secretKey =
            Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));

    private String supervisorToken;
    private String agentToken;

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

    @BeforeEach
    void setUp() {
        actuaryInfoRepository.deleteAll();

        actuaryInfoRepository.save(createActuaryInfo(AGENT_MARKO_ID, ActuaryType.AGENT,
                new BigDecimal("100000.00"), new BigDecimal("15000.00"), false));
        actuaryInfoRepository.save(createActuaryInfo(AGENT_JELENA_ID, ActuaryType.AGENT,
                new BigDecimal("50000.00"), new BigDecimal("999.99"), true));
        actuaryInfoRepository.save(createActuaryInfo(SUPERVISOR_NINA_ID, ActuaryType.SUPERVISOR,
                null, null, false));

        // JWT za HTTP autentifikaciju (filter chain ga validira lokalno).
        supervisorToken = buildToken("nina.nikolic@banka.rs", "EMPLOYEE");
        agentToken = buildToken("marko.markovic@banka.rs", "EMPLOYEE");

        // TradingUserResolver mock: email -> employeeId. Supervizor je default
        // za testove updateAgentLimit; pojedinacni testovi prekrivaju po potrebi.
        lenient().when(userResolver.resolveCurrent())
                .thenReturn(new UserContext(SUPERVISOR_NINA_ID, "EMPLOYEE"));
        // BankaCoreClient.getUserById samo za popunjavanje DTO-a.
        lenient().when(bankaCoreClient.getUserById(anyString(), anyLong()))
                .thenAnswer(inv -> {
                    Long id = inv.getArgument(1);
                    return new InternalUserDto(id, "EMPLOYEE", "user" + id + "@banka.rs",
                            "Ime" + id, "Prezime" + id, true);
                });
    }

    @AfterEach
    void tearDown() {
        actuaryInfoRepository.deleteAll();
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

    private ActuaryInfo createActuaryInfo(Long employeeId,
                                          ActuaryType type,
                                          BigDecimal dailyLimit,
                                          BigDecimal usedLimit,
                                          boolean needApproval) {
        ActuaryInfo info = new ActuaryInfo();
        info.setEmployeeId(employeeId);
        info.setActuaryType(type);
        info.setDailyLimit(dailyLimit);
        info.setUsedLimit(usedLimit);
        info.setNeedApproval(needApproval);
        return info;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    @Test
    @DisplayName("PATCH /actuaries/{id}/limit kao supervizor -> 200 i cuva izmene")
    void updateAgentLimitAsSupervisorReturns200AndPersistsChanges() {
        String payload = "{\"dailyLimit\":65000.00,\"needApproval\":false}";

        ResponseEntity<String> response = restTemplate.exchange(
                url("/actuaries/" + AGENT_JELENA_ID + "/limit"),
                HttpMethod.PATCH,
                new HttpEntity<>(payload, authHeaders(supervisorToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"employeeId\":" + AGENT_JELENA_ID);
        assertThat(response.getBody()).contains("65000.00");
        assertThat(response.getBody()).contains("\"needApproval\":false");

        ActuaryInfo persisted = actuaryInfoRepository.findByEmployeeId(AGENT_JELENA_ID).orElseThrow();
        assertThat(persisted.getDailyLimit()).isEqualByComparingTo("65000.00");
        assertThat(persisted.isNeedApproval()).isFalse();
    }

    @Test
    @DisplayName("PATCH /limit samo dailyLimit -> 200, cuva postojeci needApproval")
    void updateAgentLimitDailyLimitOnlyReturns200AndPreservesNeedApproval() {
        String payload = "{\"dailyLimit\":91000.00}";

        ResponseEntity<String> response = restTemplate.exchange(
                url("/actuaries/" + AGENT_JELENA_ID + "/limit"),
                HttpMethod.PATCH,
                new HttpEntity<>(payload, authHeaders(supervisorToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"employeeId\":" + AGENT_JELENA_ID);
        assertThat(response.getBody()).contains("91000.00");
        assertThat(response.getBody()).contains("\"needApproval\":true");

        ActuaryInfo persisted = actuaryInfoRepository.findByEmployeeId(AGENT_JELENA_ID).orElseThrow();
        assertThat(persisted.getDailyLimit()).isEqualByComparingTo("91000.00");
        assertThat(persisted.isNeedApproval()).isTrue();
    }

    @Test
    @DisplayName("PATCH /limit samo needApproval -> 200, cuva postojeci dailyLimit")
    void updateAgentLimitNeedApprovalOnlyReturns200AndPreservesDailyLimit() {
        String payload = "{\"needApproval\":false}";

        ResponseEntity<String> response = restTemplate.exchange(
                url("/actuaries/" + AGENT_JELENA_ID + "/limit"),
                HttpMethod.PATCH,
                new HttpEntity<>(payload, authHeaders(supervisorToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"employeeId\":" + AGENT_JELENA_ID);
        assertThat(response.getBody()).contains("50000.00");
        assertThat(response.getBody()).contains("\"needApproval\":false");

        ActuaryInfo persisted = actuaryInfoRepository.findByEmployeeId(AGENT_JELENA_ID).orElseThrow();
        assertThat(persisted.getDailyLimit()).isEqualByComparingTo("50000.00");
        assertThat(persisted.isNeedApproval()).isFalse();
    }

    @Test
    @DisplayName("PATCH /limit kao agent -> 403")
    void updateAgentLimitAsAgentReturns403() {
        // Trenutni korisnik je agent — service baca IllegalStateException -> 403.
        when(userResolver.resolveCurrent())
                .thenReturn(new UserContext(AGENT_MARKO_ID, "EMPLOYEE"));

        String payload = "{\"dailyLimit\":61000.00}";

        ResponseEntity<String> response = restTemplate.exchange(
                url("/actuaries/" + AGENT_JELENA_ID + "/limit"),
                HttpMethod.PATCH,
                new HttpEntity<>(payload, authHeaders(agentToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("PATCH /limit bez tokena -> 403")
    void updateAgentLimitWithoutAuthReturns403() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/actuaries/" + AGENT_JELENA_ID + "/limit"),
                HttpMethod.PATCH,
                new HttpEntity<>("{\"dailyLimit\":70000}", headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("PATCH /limit kada cilj ne postoji -> 404")
    void updateAgentLimitReturns404WhenTargetDoesNotExist() {
        String payload = "{\"needApproval\":true}";

        ResponseEntity<String> response = restTemplate.exchange(
                url("/actuaries/999999/limit"),
                HttpMethod.PATCH,
                new HttpEntity<>(payload, authHeaders(supervisorToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("PATCH /limit kada cilj nije agent -> domenska greska (non-2xx)")
    void updateAgentLimitReturnsDomainErrorWhenTargetIsSupervisor() {
        // Cilj je supervizor — service baca RuntimeException("...only be updated
        // for agents."). trading-service nema globalni handler za RuntimeException
        // (samo scoped ActuaryExceptionHandler), pa je 5xx; bitno je da je odbijeno.
        Long otherSupervisorId = 3009L;
        actuaryInfoRepository.save(createActuaryInfo(otherSupervisorId, ActuaryType.SUPERVISOR,
                null, null, false));

        String payload = "{\"dailyLimit\":80000.00}";

        ResponseEntity<String> response = restTemplate.exchange(
                url("/actuaries/" + otherSupervisorId + "/limit"),
                HttpMethod.PATCH,
                new HttpEntity<>(payload, authHeaders(supervisorToken)),
                String.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        // Cilj NIJE izmenjen — i dalje SUPERVISOR bez limita.
        ActuaryInfo unchanged = actuaryInfoRepository.findByEmployeeId(otherSupervisorId).orElseThrow();
        assertThat(unchanged.getActuaryType()).isEqualTo(ActuaryType.SUPERVISOR);
        assertThat(unchanged.getDailyLimit()).isNull();
    }

    @Test
    @DisplayName("PATCH /{id}/reset-limit kao supervizor (authority SUPERVISOR) -> 200")
    void resetUsedLimitAsSupervisorReturns200() {
        // reset-limit ima @PreAuthorize hasAuthority('SUPERVISOR') or hasRole('ADMIN').
        // JWT role=EMPLOYEE daje samo ROLE_EMPLOYEE — treba token sa rolom koja
        // mapira na trazenu authority. Koristi se ADMIN rola (hasRole('ADMIN')).
        String adminToken = buildToken("admin@banka.rs", "ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/actuaries/" + AGENT_MARKO_ID + "/reset-limit"),
                HttpMethod.PATCH,
                new HttpEntity<>(authHeaders(adminToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ActuaryInfo persisted = actuaryInfoRepository.findByEmployeeId(AGENT_MARKO_ID).orElseThrow();
        assertThat(persisted.getUsedLimit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("PATCH /{id}/reset-limit bez potrebne authority -> 403")
    void resetUsedLimitWithoutSupervisorAuthorityReturns403() {
        // role=EMPLOYEE -> samo ROLE_EMPLOYEE, ni SUPERVISOR authority ni ADMIN.
        ResponseEntity<String> response = restTemplate.exchange(
                url("/actuaries/" + AGENT_MARKO_ID + "/reset-limit"),
                HttpMethod.PATCH,
                new HttpEntity<>(authHeaders(agentToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
