package rs.raf.banka2_bek.exchange.controller;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.executable.ExecutableValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import rs.raf.banka2_bek.exchange.ExchangeService;
import rs.raf.banka2_bek.exchange.dto.CalculateExchangeResponseDto;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [P2-input-validation-1 / R1 366] Method-level Bean Validation za
 * {@code ExchangeController.calculate(@Positive amount, ...)} — verifikuje da
 * negativan/0 iznos pada constraint validaciju (→ 400 u prod-u).
 */
class ExchangeControllerValidationTest {

    private static ValidatorFactory factory;
    private static ExecutableValidator execValidator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        execValidator = validator.forExecutables();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private Method calculateMethod() throws NoSuchMethodException {
        return ExchangeController.class.getMethod(
                "calculate", double.class, String.class, String.class);
    }

    @Test
    void negativeAmount_violatesPositive() throws Exception {
        ExchangeController controller = new ExchangeController(mock(ExchangeService.class));
        Method m = calculateMethod();
        Object[] params = {-5.0, "EUR", "RSD"};

        Set<ConstraintViolation<ExchangeController>> violations =
                execValidator.validateParameters(controller, m, params);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().contains("amount"));
    }

    @Test
    void zeroAmount_violatesPositive() throws Exception {
        ExchangeController controller = new ExchangeController(mock(ExchangeService.class));
        Method m = calculateMethod();
        Object[] params = {0.0, "EUR", "RSD"};

        Set<ConstraintViolation<ExchangeController>> violations =
                execValidator.validateParameters(controller, m, params);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void positiveAmount_noViolation() throws Exception {
        ExchangeController controller = new ExchangeController(mock(ExchangeService.class));
        Method m = calculateMethod();
        Object[] params = {100.0, "EUR", "RSD"};

        Set<ConstraintViolation<ExchangeController>> violations =
                execValidator.validateParameters(controller, m, params);

        assertThat(violations).isEmpty();
    }
}
