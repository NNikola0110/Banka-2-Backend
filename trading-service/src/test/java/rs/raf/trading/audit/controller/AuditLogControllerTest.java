package rs.raf.trading.audit.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
import org.springframework.web.util.UriComponentsBuilder;
import rs.raf.trading.audit.dto.AuditLogDto;
import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.service.AuditLogService;
import rs.raf.trading.client.BankaCoreClient;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TODO_testovi Sc40/41/43/44/45 — trgovinski audit log (LIMIT_CHANGED, ORDER_APPROVED,
 * TAX_RUN_TRIGGERED) je dostizan kroz api-gateway TEK posle dodate
 * {@code location /audit -> trading-service} rute (nginx, ova batch). Sam routing
 * kroz nginx se verifikuje na finalnom Docker smoke-u (nginx se ne moze unit-testirati);
 * ovi HTTP testovi pokrivaju da kontroler ispravno mapira query parametre na servisni
 * sloj i da telo nosi tražena audit polja.
 *
 * <p>Pun {@code @SpringBootTest(RANDOM_PORT)} (mirror {@code AuditEndpointSecurityTest})
 * — dobija pravu Spring serijalizaciju ({@code Page} -> {@code PagedModel}, JSR-310),
 * realan {@code TradingSecurityConfig} filter chain, ADMIN JWT mintan lokalno deljenim
 * test secret-om. {@link AuditLogService} i {@link BankaCoreClient} su {@code @MockitoBean}
 * tako da test izoluje BAS kontroler-na-servis mapiranje (bez DB-a).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuditLogControllerTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    @Value("${local.server.port}")
    private int port;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
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

    private String adminToken() {
        return Jwts.builder()
                .subject("admin@test.com")
                .claim("role", "ADMIN")
                .claim("active", true)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000L))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Gradi URI preko {@link UriComponentsBuilder} (queryParam radi pravi encoding —
     * RestTemplate ne bi smeo da dvostruko enkoduje vec-enkodovan String URL). Svaki
     * call: {@code key=value&key2=value2}; vrednosti se prosledjuju neenkodovane.
     */
    private ResponseEntity<String> getAudit(String rawQuery) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken());
        UriComponentsBuilder b = UriComponentsBuilder
                .fromUriString("http://localhost:" + port + "/audit");
        String q = rawQuery;
        if (q != null && q.startsWith("?")) {
            q = q.substring(1);
        }
        if (q != null && !q.isEmpty()) {
            for (String pair : q.split("&")) {
                int eq = pair.indexOf('=');
                String key = eq >= 0 ? pair.substring(0, eq) : pair;
                String val = eq >= 0 ? pair.substring(eq + 1) : "";
                b.queryParam(key, val);
            }
        }
        return restTemplate.exchange(
                b.encode().build().toUri(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    private AuditLogDto dto(long id, AuditActionType type, long actorId,
                           String actorName, String targetType, Long targetId,
                           String oldValue, String newValue) {
        return AuditLogDto.builder()
                .id(id)
                .actorId(actorId)
                .actorType("EMPLOYEE")
                .actorName(actorName)
                .actionType(type.name())
                .description(type.name() + " by " + actorName)
                .targetType(targetType)
                .targetId(targetId)
                .oldValue(oldValue)
                .newValue(newValue)
                .createdAt(LocalDateTime.of(2026, 6, 2, 10, 0))
                .build();
    }

    private JsonNode firstContent(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readTree(resp.getBody()).path("content").get(0);
    }

    @Test
    @DisplayName("Sc40 — promena limita agentu: audit zapis nosi aktera, vreme, staru i novu vrednost")
    void sc40_limitChange_isReturnedWithOldAndNewValue() throws Exception {
        Page<AuditLogDto> page = new PageImpl<>(List.of(
                dto(1L, AuditActionType.LIMIT_CHANGED, 5L, "Nikola Milenkovic",
                        "EMPLOYEE", 9L, "100000", "150000")));
        when(auditLogService.query(eq(AuditActionType.LIMIT_CHANGED), isNull(),
                isNull(), isNull(), any(Pageable.class))).thenReturn(page);

        ResponseEntity<String> resp = getAudit("?actionType=LIMIT_CHANGED");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode entry = firstContent(resp);
        assertThat(entry.path("actionType").asText()).isEqualTo("LIMIT_CHANGED");
        assertThat(entry.path("actorName").asText()).isEqualTo("Nikola Milenkovic");
        assertThat(entry.path("oldValue").asText()).isEqualTo("100000");
        assertThat(entry.path("newValue").asText()).isEqualTo("150000");
        assertThat(entry.path("createdAt").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("Sc41 — odobravanje ordera: audit zapis nosi ime supervizora, id ordera, timestamp")
    void sc41_orderApproved_isReturned() throws Exception {
        when(auditLogService.query(eq(AuditActionType.ORDER_APPROVED), isNull(),
                isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        dto(2L, AuditActionType.ORDER_APPROVED, 5L, "Nikola Milenkovic",
                                "ORDER", 77L, null, null))));

        ResponseEntity<String> resp = getAudit("?actionType=ORDER_APPROVED");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode entry = firstContent(resp);
        assertThat(entry.path("actionType").asText()).isEqualTo("ORDER_APPROVED");
        assertThat(entry.path("actorName").asText()).isEqualTo("Nikola Milenkovic");
        assertThat(entry.path("targetType").asText()).isEqualTo("ORDER");
        assertThat(entry.path("targetId").asLong()).isEqualTo(77L);
        assertThat(entry.path("createdAt").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("Sc43 — rucni obracun poreza: TAX_RUN_TRIGGERED zapis dostupan kroz /audit")
    void sc43_taxRun_isReturned() throws Exception {
        when(auditLogService.query(eq(AuditActionType.TAX_RUN_TRIGGERED), isNull(),
                isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        dto(3L, AuditActionType.TAX_RUN_TRIGGERED, 5L, "Nikola Milenkovic",
                                null, null, null, null))));

        ResponseEntity<String> resp = getAudit("?actionType=TAX_RUN_TRIGGERED");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode entry = firstContent(resp);
        assertThat(entry.path("actionType").asText()).isEqualTo("TAX_RUN_TRIGGERED");
        assertThat(entry.path("actorName").asText()).isEqualTo("Nikola Milenkovic");
    }

    @Test
    @DisplayName("Sc44 — filter po tipu akcije: actionType=LIMIT_CHANGED se prosledjuje servisu kao enum")
    void sc44_filterByActionType_passesParsedEnum() {
        when(auditLogService.query(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(getAudit("?actionType=LIMIT_CHANGED").getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<AuditActionType> typeCaptor = ArgumentCaptor.forClass(AuditActionType.class);
        verify(auditLogService).query(typeCaptor.capture(), isNull(), isNull(), isNull(), any(Pageable.class));
        assertThat(typeCaptor.getValue()).isEqualTo(AuditActionType.LIMIT_CHANGED);
    }

    @Test
    @DisplayName("Sc44 — nepoznat actionType -> 400 (ne nemi prazan rezultat)")
    void sc44_unknownActionType_returns400() {
        assertThat(getAudit("?actionType=NE_POSTOJI").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(auditLogService, never()).query(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Sc45 — filter po IMENU aktera: actorName ide na queryByActorName (ne query po actorId)")
    void sc45_filterByActorName_usesNameQuery() throws Exception {
        when(auditLogService.queryByActorName(isNull(), eq("Nikola Milenkovic"),
                isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        dto(4L, AuditActionType.LIMIT_CHANGED, 5L, "Nikola Milenkovic",
                                "EMPLOYEE", 9L, "100000", "150000"))));

        ResponseEntity<String> resp = getAudit("actorName=Nikola Milenkovic");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstContent(resp).path("actorName").asText()).isEqualTo("Nikola Milenkovic");

        verify(auditLogService).queryByActorName(isNull(), eq("Nikola Milenkovic"),
                isNull(), isNull(), any(Pageable.class));
        // Ne sme da padne na numericki query kad je dat samo actorName.
        verify(auditLogService, never()).query(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Sc45 — numericki actorId ima prednost nad actorName (oba data -> query po ID-u)")
    void sc45_actorIdTakesPrecedenceOverName() {
        when(auditLogService.query(isNull(), eq(5L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(getAudit("actorId=5&actorName=Nikola Milenkovic").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        verify(auditLogService).query(isNull(), eq(5L), isNull(), isNull(), any(Pageable.class));
        verify(auditLogService, never()).queryByActorName(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("default sort je createdAt DESC, page/size se prosledjuju")
    void defaultPageableHasCreatedAtDescSort() {
        when(auditLogService.query(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(getAudit("?page=2&size=7").getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogService).query(isNull(), isNull(), isNull(), isNull(), pageableCaptor.capture());
        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageNumber()).isEqualTo(2);
        assertThat(captured.getPageSize()).isEqualTo(7);
        assertThat(captured.getSort().getOrderFor("createdAt")).isNotNull();
        assertThat(captured.getSort().getOrderFor("createdAt").isDescending()).isTrue();
    }

    @Test
    @DisplayName("from/to ISO LocalDateTime se parsiraju i prosledjuju servisu")
    void fromToAreParsedToLocalDateTime() {
        when(auditLogService.query(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(getAudit("?from=2026-06-01T00:00:00&to=2026-06-02T23:59:59").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        verify(auditLogService).query(isNull(), isNull(),
                eq(LocalDateTime.of(2026, 6, 1, 0, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 2, 23, 59, 59)),
                any(Pageable.class));
    }
}
