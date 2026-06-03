package rs.raf.banka2_bek.auth.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetDtoTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void noArgsConstructorLeavesFieldsNull() {
        PasswordResetDto dto = new PasswordResetDto();
        assertThat(dto.getToken()).isNull();
        assertThat(dto.getNewPassword()).isNull();
    }

    @Test
    void allArgsConstructorSetsFields() {
        PasswordResetDto dto = new PasswordResetDto("tok-1", "NewPass11");
        assertThat(dto.getToken()).isEqualTo("tok-1");
        assertThat(dto.getNewPassword()).isEqualTo("NewPass11");
    }

    @Test
    void settersUpdateAllFields() {
        PasswordResetDto dto = new PasswordResetDto();
        dto.setToken("t");
        dto.setNewPassword("Aa11aaaa");
        assertThat(dto.getToken()).isEqualTo("t");
        assertThat(dto.getNewPassword()).isEqualTo("Aa11aaaa");
    }

    // ── TEST-auth-11: BE-mirror lozinka-policy (Sc10) na reset flow-u ──────────
    // AuthService.resetPassword ne radi sopstvenu service-level validaciju jacine
    // lozinke — oslanja se na bean-validation @Size(8..32) + @Pattern (min 1
    // lowercase, 1 uppercase, 2 cifre) na PasswordResetDto. Ovi testovi pinuju da
    // taj BE mirror postoji i da ne moze regresirati (npr. neko skine @Pattern pa
    // slaba lozinka prodje do encode-a). Pre ovih testova DTO je imao SAMO
    // konstruktor/setter pokrivenost — nula validacijskih asercija.

    private PasswordResetDto dto(String newPassword) {
        return new PasswordResetDto("valid-token", newPassword);
    }

    @Test
    void validStrongPassword_noViolation() {
        Set<ConstraintViolation<PasswordResetDto>> violations = validator.validate(dto("NewPass12"));
        assertThat(violations).isEmpty();
    }

    @Test
    void blankToken_hasViolation() {
        Set<ConstraintViolation<PasswordResetDto>> violations =
                validator.validate(new PasswordResetDto("", "NewPass12"));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("token"));
    }

    @Test
    void blankPassword_hasViolation() {
        Set<ConstraintViolation<PasswordResetDto>> violations = validator.validate(dto(""));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("newPassword"));
    }

    @Test
    void passwordTooShort_hasViolation() {
        // 7 char (min je 8) — i pada @Size i @Pattern (samo 1 cifra), ali oba na newPassword.
        Set<ConstraintViolation<PasswordResetDto>> violations = validator.validate(dto("Abcde1f"));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("newPassword"));
    }

    @Test
    void passwordTooLong_hasViolation() {
        // 33 char (max je 32).
        Set<ConstraintViolation<PasswordResetDto>> violations =
                validator.validate(dto("Aa12" + "x".repeat(29)));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("newPassword"));
    }

    @Test
    void passwordNoUppercase_hasViolation() {
        Set<ConstraintViolation<PasswordResetDto>> violations = validator.validate(dto("abcdefg12"));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("newPassword"));
    }

    @Test
    void passwordNoLowercase_hasViolation() {
        Set<ConstraintViolation<PasswordResetDto>> violations = validator.validate(dto("ABCDEFG12"));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("newPassword"));
    }

    @Test
    void passwordOnlyOneDigit_hasViolation() {
        // Sc10 trazi >=2 cifre; ova ima samo 1.
        Set<ConstraintViolation<PasswordResetDto>> violations = validator.validate(dto("Abcdefgh1"));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("newPassword"));
    }

    @Test
    void passwordExactlyEightChars_twoDigits_noViolation() {
        // donja granica: 8 char, 1 upper, 1 lower (vise), 2 cifre.
        Set<ConstraintViolation<PasswordResetDto>> violations = validator.validate(dto("Abcdef12"));
        assertThat(violations).isEmpty();
    }
}
