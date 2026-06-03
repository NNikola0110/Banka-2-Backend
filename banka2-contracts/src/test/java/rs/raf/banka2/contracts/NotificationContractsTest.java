package rs.raf.banka2.contracts;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TEST-notif-contracts-1 (OT-CONTRACTS-DTO) — kontrakt testovi za RabbitMQ
 * notifikacione wire DTO-e i konstante.
 *
 * <p>{@code banka2-contracts} modul ranije NIJE imao test source dir, pa su se
 * inter-servisni ugovori ({@link NotificationMessage}, {@link NotificationKind},
 * {@link NotificationRabbit}) mogli neprimetno razbiti — preimenovanje polja u
 * record-u ili brisanje enum konstante tiho razbije Jackson deserijalizaciju
 * izmedju banka-core publisher-a i notification-service consumer-a (oba
 * deserijalizuju IDENTICAN tip preko {@code JacksonJsonMessageConverter}).
 *
 * <p>Koristi se Jackson 3 ({@code tools.jackson}) — isti databind koji Spring
 * AMQP 4 koristi u {@code JacksonJsonMessageConverter}-u, pa je round-trip
 * reprezentativan za stvarnu wire deserijalizaciju.
 *
 * <p>Ovi testovi su CHARAKTERIZACIONI — pinuju POSTOJECI oblik. Ako iko promeni
 * shape (npr. {@code data} → {@code payload}, ili obrise {@code NotificationKind.OTP}),
 * test pukne PRE deploy-a umesto da email pipeline tiho otkaze u produkciji.
 */
class NotificationContractsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    // ── NotificationMessage: Jackson round-trip (wire shape) ───────────────

    @Test
    void notificationMessage_serializesToExpectedJsonShape() {
        // LinkedHashMap → deterministican redosled kljuceva u asercijama.
        Map<String, String> data = new LinkedHashMap<>();
        data.put("email", "a@b.rs");
        data.put("code", "654321");
        data.put("expiryMinutes", "5");
        NotificationMessage msg = new NotificationMessage(NotificationKind.OTP, data);

        String json = mapper.writeValueAsString(msg);
        JsonNode node = mapper.readTree(json);

        // Polje 'kind' je enum string ime (ne ordinal) — to konzument ocekuje.
        assertThat(node.get("kind").asString()).isEqualTo("OTP");
        // Polje 'data' je objekat string→string.
        assertThat(node.get("data").get("email").asString()).isEqualTo("a@b.rs");
        assertThat(node.get("data").get("code").asString()).isEqualTo("654321");
        assertThat(node.get("data").get("expiryMinutes").asString()).isEqualTo("5");
        // Nema viskova polja — wire oblik je tacno {kind, data}.
        assertThat(node.propertyNames()).containsExactlyInAnyOrder("kind", "data");
    }

    @Test
    void notificationMessage_jsonRoundTrip_isLossless() {
        Map<String, String> data = Map.of(
                "email", "klijent@banka.rs",
                "amount", "1500.50",
                "currency", "RSD",
                "date", "2026-05-18");
        NotificationMessage original = new NotificationMessage(NotificationKind.PAYMENT_CONFIRMED, data);

        String json = mapper.writeValueAsString(original);
        NotificationMessage deserialized = mapper.readValue(json, NotificationMessage.class);

        assertThat(deserialized.kind()).isEqualTo(NotificationKind.PAYMENT_CONFIRMED);
        assertThat(deserialized.data()).isEqualTo(data);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void notificationMessage_unknownEnumString_failsDeserialization() {
        // Kontrakt: nepoznata 'kind' vrednost u JSON-u baca pri deserijalizaciji
        // (default Jackson enum mapping). Ovo je tacno staza koju
        // notification-service container hvata kao conversion-error → DLQ
        // (cross-ref OT-1597 / RabbitConfig defaultRequeueRejected=false).
        String badJson = "{\"kind\":\"NOPE_NOT_A_KIND\",\"data\":{}}";
        assertThatThrownBy(() -> mapper.readValue(badJson, NotificationMessage.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    // ── NotificationMessage: defanzivna kopija (immutability invarijanta) ──

    @Test
    void notificationMessage_dataIsDefensivelyCopied_andImmutable() {
        Map<String, String> mutable = new LinkedHashMap<>();
        mutable.put("email", "a@b.rs");
        NotificationMessage msg = new NotificationMessage(NotificationKind.CARD_BLOCKED, mutable);

        // Mutacija ulazne mape NE sme da utice na vec-konstruisanu poruku.
        mutable.put("injected", "x");
        assertThat(msg.data()).doesNotContainKey("injected");

        // Sama mapa u poruci je nepromenljiva (Map.copyOf).
        assertThatThrownBy(() -> msg.data().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void notificationMessage_nullValueInData_isRejected() {
        // Map.copyOf odbija null vrednosti → poison payload se ne moze
        // ni konstruisati sa null vrednoscu (defanziva na consumer-ovoj strani).
        Map<String, String> withNullValue = new LinkedHashMap<>();
        withNullValue.put("email", null);
        assertThatThrownBy(() -> new NotificationMessage(NotificationKind.OTP, withNullValue))
                .isInstanceOf(NullPointerException.class);
    }

    // ── NotificationKind: enum-exhaustiveness + stabilan wire string ───────

    @Test
    void notificationKind_hasExactlyExpectedConstants() {
        // Ako iko DODA ili OBRISE enum konstantu, ovaj pin pukne — prisiljava
        // svestan update i u notification-service dispatch switch-u
        // (NotificationConsumer#dispatch je exhaustivan; nov kind bez mapiranja → DLQ).
        assertThat(NotificationKind.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        "PASSWORD_RESET",
                        "EMPLOYEE_ACCOUNT_CREATED",
                        "EMPLOYEE_ACTIVATION_CONFIRMED",
                        "ACCOUNT_CREATED",
                        "OTP",
                        "PAYMENT_CONFIRMED",
                        "CARD_BLOCKED",
                        "CARD_UNBLOCKED",
                        "LOAN_REQUEST_SUBMITTED",
                        "LOAN_APPROVED",
                        "LOAN_REJECTED",
                        "INSTALLMENT_PAID",
                        "INSTALLMENT_FAILED",
                        "MARGIN_ACCOUNT_BLOCKED",
                        "ACCOUNT_LOCKED",
                        "IN_APP_GENERIC");
    }

    @Test
    void notificationKind_serializesByNameNotOrdinal() {
        // Kontrakt zavisi od enum IMENA (string) preko zice — NE ordinala. Ako
        // bi se serijalizovalo po ordinalu, dodavanje nove konstante u sredinu
        // bi tiho pomerilo sve poruke. Pin da je default Jackson = name-based.
        for (NotificationKind kind : NotificationKind.values()) {
            String json = mapper.writeValueAsString(kind);
            assertThat(json).isEqualTo("\"" + kind.name() + "\"");
            assertThat(mapper.readValue(json, NotificationKind.class)).isEqualTo(kind);
        }
    }

    @Test
    void notificationKind_namesAreNonBlankAndUnique() {
        List<String> names = java.util.Arrays.stream(NotificationKind.values())
                .map(Enum::name).toList();
        assertThat(names).allSatisfy(n -> assertThat(n).isNotBlank());
        assertThat(names).doesNotHaveDuplicates();
    }

    // ── NotificationRabbit: topologija konstante (publisher↔consumer slazu) ─

    @Test
    void notificationRabbit_topologyConstantsAreStable() {
        // Publisher (banka-core NotificationPublisher) i consumer
        // (notification-service NotificationConsumer / RabbitConfig) referenciraju
        // ISTE konstante. Ako se vrednost promeni, poruke odu na pogresan
        // exchange/queue i tiho nestanu — ovaj pin to spreci.
        assertThat(NotificationRabbit.EXCHANGE).isEqualTo("banka2.events");
        assertThat(NotificationRabbit.EMAIL_ROUTING_KEY).isEqualTo("notification.email");
        assertThat(NotificationRabbit.EMAIL_QUEUE).isEqualTo("notification.email.q");
    }

    // ── InternalNotificationRequest: cross-DB in-app wire shape ────────────

    @Test
    void internalNotificationRequest_jsonRoundTrip_preservesAllFields() {
        var req = new rs.raf.banka2.contracts.internal.InternalNotificationRequest(
                42L, "CLIENT", "ORDER_EXECUTED", "Order izvrsen",
                "Vas BUY order je popunjen", "ORDER", 99L, "idem-key-1");

        String json = mapper.writeValueAsString(req);
        var back = mapper.readValue(json,
                rs.raf.banka2.contracts.internal.InternalNotificationRequest.class);

        assertThat(back).isEqualTo(req);
        // Field-shape pin: ime polja 'message' (NE 'body') i 'recipientType' (NE 'role')
        // — banka-core NotificationServiceImpl.createInternalNotification cita bas ova imena.
        JsonNode node = mapper.readTree(json);
        assertThat(node.propertyNames()).containsExactlyInAnyOrder(
                "recipientId", "recipientType", "type", "title",
                "message", "referenceType", "referenceId", "idempotencyKey");
    }

    @Test
    void internalNotificationRequest_optionalFields_serializeAsNull() {
        // referenceType/referenceId su opcioni (deep-link). null mora da se
        // serijalizuje kao JSON null (ne da nestane) tako da consumer pouzdano
        // moze da razlikuje "nema reference" → dedup se ne primenjuje.
        var req = new rs.raf.banka2.contracts.internal.InternalNotificationRequest(
                7L, "EMPLOYEE", "GENERAL", "T", "B", null, null, "k");

        JsonNode node = mapper.readTree(mapper.writeValueAsString(req));
        assertThat(node.get("referenceType").isNull()).isTrue();
        assertThat(node.get("referenceId").isNull()).isTrue();
    }
}
