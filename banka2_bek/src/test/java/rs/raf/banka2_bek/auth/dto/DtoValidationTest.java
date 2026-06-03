package rs.raf.banka2_bek.auth.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import rs.raf.banka2_bek.employee.dto.CreateEmployeeRequestDto;
import rs.raf.banka2_bek.payment.dto.CreatePaymentRequestDto;
import rs.raf.banka2_bek.card.dto.CreateCardRequestDto;
import rs.raf.banka2_bek.card.model.CardType;
import rs.raf.banka2_bek.account.dto.CreateAccountDto;
import rs.raf.banka2_bek.account.model.AccountType;
import rs.raf.banka2_bek.payment.model.PaymentCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // ── LoginRequestDto ─────────────────────────────────────────────

    @Nested
    class LoginRequestDtoTests {

        @Test
        void validDto_noViolations() {
            LoginRequestDto dto = new LoginRequestDto("user@test.com", "password123");
            Set<ConstraintViolation<LoginRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }

        @Test
        void blankEmail_hasViolation() {
            LoginRequestDto dto = new LoginRequestDto("", "password123");
            Set<ConstraintViolation<LoginRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("email"));
        }

        @Test
        void nullEmail_hasViolation() {
            LoginRequestDto dto = new LoginRequestDto(null, "password123");
            Set<ConstraintViolation<LoginRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void invalidEmailFormat_hasViolation() {
            LoginRequestDto dto = new LoginRequestDto("not-an-email", "password123");
            Set<ConstraintViolation<LoginRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getMessage().contains("Email format is invalid"));
        }

        @Test
        void blankPassword_hasViolation() {
            LoginRequestDto dto = new LoginRequestDto("user@test.com", "");
            Set<ConstraintViolation<LoginRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
        }

        @Test
        void nullPassword_hasViolation() {
            LoginRequestDto dto = new LoginRequestDto("user@test.com", null);
            Set<ConstraintViolation<LoginRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }
    }

    // ── RegisterRequestDto ──────────────────────────────────────────

    @Nested
    class RegisterRequestDtoTests {

        private RegisterRequestDto buildValid() {
            RegisterRequestDto dto = new RegisterRequestDto();
            dto.setFirstName("Marko");
            dto.setLastName("Petrovic");
            dto.setEmail("marko@test.com");
            dto.setPassword("Passw0rd12");
            return dto;
        }

        @Test
        void validDto_noViolations() {
            Set<ConstraintViolation<RegisterRequestDto>> violations = validator.validate(buildValid());
            assertThat(violations).isEmpty();
        }

        @Test
        void blankFirstName_hasViolation() {
            RegisterRequestDto dto = buildValid();
            dto.setFirstName("");
            Set<ConstraintViolation<RegisterRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("firstName"));
        }

        @Test
        void blankLastName_hasViolation() {
            RegisterRequestDto dto = buildValid();
            dto.setLastName("");
            Set<ConstraintViolation<RegisterRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void invalidEmail_hasViolation() {
            RegisterRequestDto dto = buildValid();
            dto.setEmail("invalid");
            Set<ConstraintViolation<RegisterRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void passwordTooShort_hasViolation() {
            RegisterRequestDto dto = buildValid();
            dto.setPassword("Ab12");
            Set<ConstraintViolation<RegisterRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void passwordNoDigits_hasViolation() {
            RegisterRequestDto dto = buildValid();
            dto.setPassword("Abcdefghij");
            Set<ConstraintViolation<RegisterRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void passwordNoUppercase_hasViolation() {
            RegisterRequestDto dto = buildValid();
            dto.setPassword("abcdefg12");
            Set<ConstraintViolation<RegisterRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void passwordNoLowercase_hasViolation() {
            RegisterRequestDto dto = buildValid();
            dto.setPassword("ABCDEFG12");
            Set<ConstraintViolation<RegisterRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void passwordOnlyOneDigit_hasViolation() {
            RegisterRequestDto dto = buildValid();
            dto.setPassword("Abcdefgh1");
            Set<ConstraintViolation<RegisterRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void passwordTooLong_hasViolation() {
            RegisterRequestDto dto = buildValid();
            dto.setPassword("Aa12" + "x".repeat(30));
            Set<ConstraintViolation<RegisterRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void phoneTooLong_hasViolation() {
            RegisterRequestDto dto = buildValid();
            dto.setPhone("1".repeat(21));
            Set<ConstraintViolation<RegisterRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void invalidPhoneFormat_hasViolation() {
            RegisterRequestDto dto = buildValid();
            dto.setPhone("06-123-456");
            Set<ConstraintViolation<RegisterRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("phone"));
        }

        @Test
        void validPhoneWithPlus_noViolation() {
            RegisterRequestDto dto = buildValid();
            dto.setPhone("+381601234567");
            Set<ConstraintViolation<RegisterRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }

        @Test
        void futureDateOfBirth_hasViolation() {
            RegisterRequestDto dto = buildValid();
            dto.setDateOfBirth(System.currentTimeMillis() + 86_400_000L);
            Set<ConstraintViolation<RegisterRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("dateOfBirthValid"));
        }

        @Test
        void pastDateOfBirth_noViolation() {
            RegisterRequestDto dto = buildValid();
            dto.setDateOfBirth(LocalDate.of(1990, 1, 1)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli());
            Set<ConstraintViolation<RegisterRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }
    }

    // ── CreateEmployeeRequestDto ────────────────────────────────────

    @Nested
    class CreateEmployeeRequestDtoTests {

        private CreateEmployeeRequestDto buildValid() {
            CreateEmployeeRequestDto dto = new CreateEmployeeRequestDto();
            dto.setFirstName("Jovan");
            dto.setLastName("Jovanovic");
            dto.setDateOfBirth(LocalDate.of(1990, 5, 15));
            dto.setGender("M");
            dto.setEmail("jovan@banka.rs");
            dto.setPhone("0611234567");
            dto.setAddress("Knez Mihailova 1");
            dto.setUsername("jjovanovic");
            dto.setPosition("Savetnik");
            dto.setDepartment("IT");
            return dto;
        }

        @Test
        void validDto_noViolations() {
            Set<ConstraintViolation<CreateEmployeeRequestDto>> violations = validator.validate(buildValid());
            assertThat(violations).isEmpty();
        }

        @Test
        void blankFirstName_hasViolation() {
            CreateEmployeeRequestDto dto = buildValid();
            dto.setFirstName("");
            Set<ConstraintViolation<CreateEmployeeRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void nullDateOfBirth_hasViolation() {
            CreateEmployeeRequestDto dto = buildValid();
            dto.setDateOfBirth(null);
            Set<ConstraintViolation<CreateEmployeeRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void invalidEmail_hasViolation() {
            CreateEmployeeRequestDto dto = buildValid();
            dto.setEmail("not-email");
            Set<ConstraintViolation<CreateEmployeeRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void blankPhone_hasViolation() {
            CreateEmployeeRequestDto dto = buildValid();
            dto.setPhone("");
            Set<ConstraintViolation<CreateEmployeeRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void blankDepartment_hasViolation() {
            CreateEmployeeRequestDto dto = buildValid();
            dto.setDepartment("");
            Set<ConstraintViolation<CreateEmployeeRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void nullActive_isOptional_noViolation() {
            CreateEmployeeRequestDto dto = buildValid();
            dto.setActive(null);
            Set<ConstraintViolation<CreateEmployeeRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }

        @Test
        void nullPermissions_isOptional_noViolation() {
            CreateEmployeeRequestDto dto = buildValid();
            dto.setPermissions(null);
            Set<ConstraintViolation<CreateEmployeeRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }

        @Test
        void futureDateOfBirth_hasViolation() {
            CreateEmployeeRequestDto dto = buildValid();
            dto.setDateOfBirth(LocalDate.now().plusDays(1));
            Set<ConstraintViolation<CreateEmployeeRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("dateOfBirth"));
        }

        @Test
        void invalidPhoneFormat_hasViolation() {
            CreateEmployeeRequestDto dto = buildValid();
            dto.setPhone("abc-def");
            Set<ConstraintViolation<CreateEmployeeRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("phone"));
        }

        @Test
        void validPhoneWithPlus_noViolation() {
            CreateEmployeeRequestDto dto = buildValid();
            dto.setPhone("+381601234567");
            Set<ConstraintViolation<CreateEmployeeRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }
    }

    // ── CreatePaymentRequestDto ─────────────────────────────────────

    @Nested
    class CreatePaymentRequestDtoTests {

        private CreatePaymentRequestDto buildValid() {
            CreatePaymentRequestDto dto = new CreatePaymentRequestDto();
            dto.setFromAccount("2220001000000011");
            dto.setToAccount("2220001000000021");
            dto.setAmount(new BigDecimal("1500.00"));
            dto.setPaymentCode(PaymentCode.CODE_289);
            dto.setDescription("Uplata za usluge");
            dto.setOtpCode("123456");
            return dto;
        }

        @Test
        void validDto_noViolations() {
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(buildValid());
            assertThat(violations).isEmpty();
        }

        @Test
        void blankFromAccount_hasViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setFromAccount("");
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void fromAccountTooShort_hasViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setFromAccount("123456789");
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void fromAccountTooLong_hasViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setFromAccount("1".repeat(21));
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void nullAmount_hasViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setAmount(null);
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void zeroAmount_hasViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setAmount(BigDecimal.ZERO);
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void negativeAmount_hasViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setAmount(new BigDecimal("-100"));
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        // R1-645: @Digits(15,4) — vise od 4 decimale je nevalidno (kolona scale=4).
        @Test
        void amountTooManyDecimals_hasViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setAmount(new BigDecimal("10.123456"));
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("amount"));
        }

        // R1-645: @Digits(15,4) — vise od 15 cifara ispred zareza je nevalidno (precision=19).
        @Test
        void amountTooManyIntegerDigits_hasViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setAmount(new BigDecimal("1234567890123456")); // 16 cifara
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("amount"));
        }

        @Test
        void amountFourDecimals_noAmountViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setAmount(new BigDecimal("100.1234"));
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).noneMatch(v -> v.getPropertyPath().toString().equals("amount"));
        }

        @Test
        void nullPaymentCode_hasViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setPaymentCode(null);
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void blankDescription_hasViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setDescription("");
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void blankOtpCode_hasViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setOtpCode("");
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void nullReferenceNumber_isOptional_noViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setReferenceNumber(null);
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }

        // ── [P2-input-validation-1 / R1 327] toAccount > 18 (kolona length=18) ──

        @Test
        void toAccount19Digits_hasViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setToAccount("1".repeat(19));
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("toAccount"));
        }

        @Test
        void toAccount18Digits_noViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setToAccount("1".repeat(18));
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }

        // ── [P2-input-validation-1 / R1 328] description > 200 (kolona purpose=200) ──

        @Test
        void description201Chars_hasViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setDescription("x".repeat(201));
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("description"));
        }

        @Test
        void description200Chars_noViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setDescription("x".repeat(200));
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }

        // ── [P2-input-validation-1 / R1 331] OTP @Pattern \d{6} ──────────────────

        @Test
        void otpCodeNonNumeric_hasViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setOtpCode("abcdef");
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("otpCode"));
        }

        @Test
        void otpCodeWrongLength_hasViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setOtpCode("12345");
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void otpCodeSixDigits_noViolation() {
            CreatePaymentRequestDto dto = buildValid();
            dto.setOtpCode("123456");
            Set<ConstraintViolation<CreatePaymentRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }
    }

    // ── CreateAccountDto ────────────────────────────────────────────

    @Nested
    class CreateAccountDtoTests {

        private CreateAccountDto buildValid() {
            CreateAccountDto dto = new CreateAccountDto();
            dto.setAccountType(AccountType.CHECKING);
            dto.setInitialBalance(BigDecimal.ZERO);
            return dto;
        }

        @Test
        void validDto_noViolations() {
            Set<ConstraintViolation<CreateAccountDto>> violations = validator.validate(buildValid());
            assertThat(violations).isEmpty();
        }

        @Test
        void nullAccountType_hasViolation() {
            CreateAccountDto dto = buildValid();
            dto.setAccountType(null);
            Set<ConstraintViolation<CreateAccountDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void negativeInitialBalance_hasViolation() {
            CreateAccountDto dto = buildValid();
            dto.setInitialBalance(new BigDecimal("-100"));
            Set<ConstraintViolation<CreateAccountDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }

        @Test
        void zeroInitialBalance_noViolation() {
            CreateAccountDto dto = buildValid();
            dto.setInitialBalance(BigDecimal.ZERO);
            Set<ConstraintViolation<CreateAccountDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }

        @Test
        void positiveInitialBalance_noViolation() {
            CreateAccountDto dto = buildValid();
            dto.setInitialBalance(new BigDecimal("5000"));
            Set<ConstraintViolation<CreateAccountDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }

        @Test
        void resolvedCurrencyCode_prefersCurrencyCode() {
            CreateAccountDto dto = buildValid();
            dto.setCurrencyCode("EUR");
            dto.setCurrency("RSD");
            assertThat(dto.getResolvedCurrencyCode()).isEqualTo("EUR");
        }

        @Test
        void resolvedCurrencyCode_fallsToCurrency() {
            CreateAccountDto dto = buildValid();
            dto.setCurrencyCode(null);
            dto.setCurrency("RSD");
            assertThat(dto.getResolvedCurrencyCode()).isEqualTo("RSD");
        }

        @Test
        void resolvedCurrencyCode_bothNull_returnsNull() {
            CreateAccountDto dto = buildValid();
            dto.setCurrencyCode(null);
            dto.setCurrency(null);
            assertThat(dto.getResolvedCurrencyCode()).isNull();
        }

        @Test
        void resolvedInitialBalance_prefersInitialBalance() {
            CreateAccountDto dto = buildValid();
            dto.setInitialBalance(new BigDecimal("1000"));
            dto.setInitialDeposit(500.0);
            assertThat(dto.getResolvedInitialBalance()).isEqualByComparingTo(new BigDecimal("1000"));
        }

        @Test
        void resolvedInitialBalance_fallsToInitialDeposit() {
            CreateAccountDto dto = buildValid();
            dto.setInitialBalance(null);
            dto.setInitialDeposit(750.0);
            assertThat(dto.getResolvedInitialBalance()).isEqualByComparingTo(new BigDecimal("750"));
        }

        @Test
        void resolvedInitialBalance_bothNull_returnsZero() {
            CreateAccountDto dto = buildValid();
            dto.setInitialBalance(null);
            dto.setInitialDeposit(null);
            assertThat(dto.getResolvedInitialBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        // ── [P2-input-validation-1 / R1 310] negativan initialDeposit ────────────

        @Test
        void negativeInitialDeposit_hasViolation() {
            CreateAccountDto dto = buildValid();
            dto.setInitialDeposit(-100.0);
            Set<ConstraintViolation<CreateAccountDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("initialDeposit"));
        }

        @Test
        void zeroInitialDeposit_noViolation() {
            CreateAccountDto dto = buildValid();
            dto.setInitialDeposit(0.0);
            Set<ConstraintViolation<CreateAccountDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }

        // ── [P2-input-validation-1 / R1 312] sifra delatnosti xx.xx format ───────

        @Test
        void invalidActivityCode_hasViolation() {
            CreateAccountDto dto = buildValid();
            dto.setActivityCode("6201"); // bez tacke
            Set<ConstraintViolation<CreateAccountDto>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("activityCode"));
        }

        @Test
        void validActivityCode_noViolation() {
            CreateAccountDto dto = buildValid();
            dto.setActivityCode("62.01");
            Set<ConstraintViolation<CreateAccountDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }

        @Test
        void blankActivityCode_isOptional_noViolation() {
            CreateAccountDto dto = buildValid();
            dto.setActivityCode("");
            Set<ConstraintViolation<CreateAccountDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }

        // ── TEST-accounts-10: AD/FONDACIJA subtype → BE 400 (error-contract) ─────
        // Celina 2 §43 pominje poslovne podvrste "DOO, AD, fondacija", ALI
        // AccountSubtype enum NE sadrzi AD/FONDACIJA (samo PERSONAL/SAVINGS/PENSION/
        // YOUTH/STUDENT/UNEMPLOYED/SALARY/STANDARD). Karakterizacija error-contract-a:
        // JSON sa "accountSubtype":"AD" se NE moze deserijalizovati u CreateAccountDto
        // (Jackson baca InvalidFormatException → Spring 400 Bad Request), tj. NEMA
        // tihog coerce-a u null/STANDARD. Pinujemo i deserijalizaciju i enum.valueOf.

        @Test
        void accountSubtypeAD_jsonDeserialization_fails() throws Exception {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String json = "{\"accountType\":\"BUSINESS\",\"accountSubtype\":\"AD\"}";
            org.junit.jupiter.api.Assertions.assertThrows(
                    com.fasterxml.jackson.databind.exc.InvalidFormatException.class,
                    () -> mapper.readValue(json, CreateAccountDto.class));
        }

        @Test
        void accountSubtypeFONDACIJA_jsonDeserialization_fails() throws Exception {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String json = "{\"accountType\":\"BUSINESS\",\"accountSubtype\":\"FONDACIJA\"}";
            org.junit.jupiter.api.Assertions.assertThrows(
                    com.fasterxml.jackson.databind.exc.InvalidFormatException.class,
                    () -> mapper.readValue(json, CreateAccountDto.class));
        }

        @Test
        void accountSubtypeAD_isNotAValidEnumConstant() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> rs.raf.banka2_bek.account.model.AccountSubtype.valueOf("AD"));
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> rs.raf.banka2_bek.account.model.AccountSubtype.valueOf("FONDACIJA"));
        }

        @Test
        void knownBusinessSubtypeSTANDARD_deserializesFine() throws Exception {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String json = "{\"accountType\":\"BUSINESS\",\"accountSubtype\":\"STANDARD\"}";
            CreateAccountDto dto = mapper.readValue(json, CreateAccountDto.class);
            assertThat(dto.getAccountSubtype())
                    .isEqualTo(rs.raf.banka2_bek.account.model.AccountSubtype.STANDARD);
        }
    }

    // ── CreateCardRequestDto (R1-635: @PositiveOrZero na cardLimit/creditLimit) ──

    @Nested
    class CreateCardRequestDtoTests {

        private CreateCardRequestDto buildValid() {
            CreateCardRequestDto dto = new CreateCardRequestDto();
            dto.setAccountId(1L);
            dto.setCardType(CardType.VISA);
            dto.setCardLimit(new BigDecimal("100000"));
            dto.setCreditLimit(new BigDecimal("50000"));
            return dto;
        }

        @Test
        void validDto_noViolations() {
            Set<ConstraintViolation<CreateCardRequestDto>> violations = validator.validate(buildValid());
            assertThat(violations).isEmpty();
        }

        @Test
        void nullAccountId_hasViolation() {
            CreateCardRequestDto dto = buildValid();
            dto.setAccountId(null);
            Set<ConstraintViolation<CreateCardRequestDto>> violations = validator.validate(dto);
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("accountId"));
        }

        @Test
        void negativeCardLimit_hasViolation() {
            CreateCardRequestDto dto = buildValid();
            dto.setCardLimit(new BigDecimal("-1"));
            Set<ConstraintViolation<CreateCardRequestDto>> violations = validator.validate(dto);
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("cardLimit"));
        }

        @Test
        void negativeCreditLimit_hasViolation() {
            CreateCardRequestDto dto = buildValid();
            dto.setCreditLimit(new BigDecimal("-0.01"));
            Set<ConstraintViolation<CreateCardRequestDto>> violations = validator.validate(dto);
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("creditLimit"));
        }

        @Test
        void nullLimits_areOptional_noViolation() {
            CreateCardRequestDto dto = buildValid();
            dto.setCardLimit(null);
            dto.setCreditLimit(null);
            Set<ConstraintViolation<CreateCardRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }

        @Test
        void zeroLimits_noViolation() {
            CreateCardRequestDto dto = buildValid();
            dto.setCardLimit(BigDecimal.ZERO);
            dto.setCreditLimit(BigDecimal.ZERO);
            Set<ConstraintViolation<CreateCardRequestDto>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }
    }
}
