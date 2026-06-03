package rs.raf.trading.audit.dto;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5 1879 — karakterizacioni pin za trading-service {@link AuditLogDto}.
 *
 * <p>Mora drzati IDENTICAN polje-set kao banka-core {@code AuditLogDto}
 * (konsolidacija u banka2-contracts odlozena). Drift na bilo kojoj strani puca build.
 * Kanonska lista je duplirana namerno (oba modula su nezavisni compile unit-i).
 */
class AuditLogDtoContractPinTest {

    private static final Set<String> EXPECTED_FIELDS = Set.of(
            "id:java.lang.Long",
            "actorId:java.lang.Long",
            "actorType:java.lang.String",
            "actorName:java.lang.String",
            "actionType:java.lang.String",
            "description:java.lang.String",
            "targetType:java.lang.String",
            "targetId:java.lang.Long",
            "oldValue:java.lang.String",
            "newValue:java.lang.String",
            "createdAt:java.time.LocalDateTime"
    );

    @Test
    void fieldSetIsPinned() {
        Set<String> actual = Arrays.stream(AuditLogDto.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .map(f -> f.getName() + ":" + f.getType().getName())
                .collect(Collectors.toSet());

        assertThat(actual)
                .as("trading-service AuditLogDto polja moraju matchovati banka-core kopiju (R5 1879)")
                .isEqualTo(EXPECTED_FIELDS);
    }

    @Test
    void builderAndGettersRoundTrip() {
        var dto = AuditLogDto.builder()
                .id(10L).actorId(20L).actorType("CLIENT").actorName("Stefan")
                .actionType("ORDER").description("BUY").targetType("ORDER").targetId(30L)
                .oldValue("x").newValue("y").build();

        assertThat(dto.getId()).isEqualTo(10L);
        assertThat(dto.getActorName()).isEqualTo("Stefan");
        assertThat(dto.getActionType()).isEqualTo("ORDER");
        assertThat(dto.getNewValue()).isEqualTo("y");
    }

    @Test
    void hasExactlyElevenFields() {
        long count = Arrays.stream(AuditLogDto.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .count();
        assertThat(count).isEqualTo(11);
    }
}
