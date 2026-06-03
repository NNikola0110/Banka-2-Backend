package rs.raf.banka2_bek.audit.dto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5 1879 — karakterizacioni pin za banka-core {@link AuditLogDto}.
 *
 * <p>banka-core i trading-service drze DUPLIKAT {@code AuditLogDto} (konsolidacija u
 * banka2-contracts odlozena — vidi javadoc klase). Ovaj test zakljucava polje-set i
 * njihove tipove tako da svaki drift (dodato/uklonjeno/preimenovano polje na jednoj
 * strani bez druge) odmah pukne build i natera autora da uskladi obe kopije (ili da
 * konacno preseli DTO u contracts).
 */
class AuditLogDtoContractPinTest {

    /** Kanonski polje-set:tip (mora biti IDENTICAN u trading-service AuditLogDtoContractPinTest-u). */
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
                .as("banka-core AuditLogDto polja moraju matchovati trading-service kopiju (R5 1879)")
                .isEqualTo(EXPECTED_FIELDS);
    }

    @Test
    void builderAndGettersRoundTrip() {
        var dto = AuditLogDto.builder()
                .id(1L).actorId(2L).actorType("EMPLOYEE").actorName("Marko")
                .actionType("LOGIN").description("ok").targetType("USER").targetId(3L)
                .oldValue("a").newValue("b").build();

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getActorName()).isEqualTo("Marko");
        assertThat(dto.getTargetId()).isEqualTo(3L);
        assertThat(dto.getNewValue()).isEqualTo("b");
    }

    @Test
    void hasExactlyElevenFields() {
        long count = Arrays.stream(AuditLogDto.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .count();
        assertThat(count).isEqualTo(11);
    }
}
