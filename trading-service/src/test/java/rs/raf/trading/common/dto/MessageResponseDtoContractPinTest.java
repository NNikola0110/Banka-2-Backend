package rs.raf.trading.common.dto;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5 1879 — karakterizacioni pin za trading-service {@link MessageResponseDto}.
 *
 * <p>Duplikat banka-core {@code auth.dto.MessageResponseDto} (konsolidacija u
 * banka2-contracts odlozena). Pin zakljucava jedinstveno {@code message} polje +
 * (no-args / all-args) konstruktore tako da drift puca build.
 */
class MessageResponseDtoContractPinTest {

    private static final Set<String> EXPECTED_FIELDS = Set.of("message:java.lang.String");

    @Test
    void fieldSetIsPinned() {
        Set<String> actual = Arrays.stream(MessageResponseDto.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .map(f -> f.getName() + ":" + f.getType().getName())
                .collect(Collectors.toSet());

        assertThat(actual)
                .as("trading MessageResponseDto mora drzati samo {message} (R5 1879)")
                .isEqualTo(EXPECTED_FIELDS);
    }

    @Test
    void constructorsRoundTrip() {
        assertThat(new MessageResponseDto().getMessage()).isNull();
        MessageResponseDto dto = new MessageResponseDto("greska");
        assertThat(dto.getMessage()).isEqualTo("greska");
        dto.setMessage("druga");
        assertThat(dto.getMessage()).isEqualTo("druga");
    }
}
