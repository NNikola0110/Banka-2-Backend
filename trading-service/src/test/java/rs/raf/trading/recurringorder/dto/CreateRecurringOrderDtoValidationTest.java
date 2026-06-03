package rs.raf.trading.recurringorder.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.raf.trading.recurringorder.model.RecurringCadence;
import rs.raf.trading.recurringorder.model.RecurringMode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P2-input-validation-1 / R1 528] Bean Validation za {@link CreateRecurringOrderDto}
 * — firstRun @Future.
 */
class CreateRecurringOrderDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private CreateRecurringOrderDto valid() {
        CreateRecurringOrderDto dto = new CreateRecurringOrderDto();
        dto.setListingId(1L);
        dto.setDirection("BUY");
        dto.setMode(RecurringMode.BY_QUANTITY);
        dto.setValue(new BigDecimal("5"));
        dto.setAccountId(1L);
        dto.setCadence(RecurringCadence.DAILY);
        return dto;
    }

    @Test
    void validDto_noViolations() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    @Test
    void firstRunInFuture_noViolation() {
        CreateRecurringOrderDto dto = valid();
        dto.setFirstRun(LocalDateTime.now().plusDays(2));
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    void firstRunInPast_hasViolation() {
        CreateRecurringOrderDto dto = valid();
        dto.setFirstRun(LocalDateTime.now().minusDays(2));
        Set<ConstraintViolation<CreateRecurringOrderDto>> v = validator.validate(dto);
        assertThat(v).isNotEmpty();
        assertThat(v).anyMatch(c -> c.getPropertyPath().toString().equals("firstRun"));
    }

    @Test
    void nullFirstRun_isOptional_noViolation() {
        CreateRecurringOrderDto dto = valid();
        dto.setFirstRun(null);
        assertThat(validator.validate(dto)).isEmpty();
    }
}
