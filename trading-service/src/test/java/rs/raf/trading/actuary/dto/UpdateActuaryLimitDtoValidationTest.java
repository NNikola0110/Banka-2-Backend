package rs.raf.trading.actuary.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-9 (Celina 3 S4): Bean Validation za {@link UpdateActuaryLimitDto}.
 *
 * <p>Spec S4: dnevni limit od 0 ili negativan se odbija. Pre fix-a je
 * {@code @DecimalMin(value="0")} (inclusive) propustao 0; sad je
 * {@code inclusive=false} pa i 0 pada validaciju.
 */
class UpdateActuaryLimitDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    private UpdateActuaryLimitDto dtoWithLimit(BigDecimal limit) {
        UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
        dto.setDailyLimit(limit);
        return dto;
    }

    @Test
    @DisplayName("limit = 0 se odbija (inclusive=false)")
    void rejectsZero() {
        var violations = validator.validate(dtoWithLimit(BigDecimal.ZERO));
        assertFalse(violations.isEmpty(), "limit=0 mora pasti validaciju");
    }

    @Test
    @DisplayName("negativan limit se odbija")
    void rejectsNegative() {
        var violations = validator.validate(dtoWithLimit(new BigDecimal("-100")));
        assertFalse(violations.isEmpty(), "negativan limit mora pasti validaciju");
    }

    @Test
    @DisplayName("pozitivan limit prolazi")
    void acceptsPositive() {
        var violations = validator.validate(dtoWithLimit(new BigDecimal("100000")));
        assertTrue(violations.isEmpty(), "pozitivan limit mora proci validaciju");
    }

    @Test
    @DisplayName("null limit prolazi (parcijalni update — menja se samo needApproval)")
    void acceptsNull() {
        var violations = validator.validate(dtoWithLimit(null));
        assertTrue(violations.isEmpty(), "null limit mora proci validaciju (opciono polje)");
    }
}
