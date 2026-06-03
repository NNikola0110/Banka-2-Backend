package rs.raf.banka2_bek.schema;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.company.model.Company;
import rs.raf.banka2_bek.interbank.model.InterbankOtcContract;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-schema-1: bean-validation invarijante na entitetima koje ne zavise od baze
 * (scale==0 integer guard, single-owner @AssertTrue). DB-level constraint-i
 * (CHECK, unique-index dedup) su pokriveni odvojenim Testcontainers PG smoke
 * testovima (vidi {@code AccountCheckConstraintPostgresDdlIT} /
 * {@code FundReservationUniquePostgresDdlIT}).
 */
class EntityConstraintTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Nested
    class InterbankOtcContractQuantity_R4_1773 {

        private InterbankOtcContract base() {
            InterbankOtcContract c = new InterbankOtcContract();
            c.setQuantity(new BigDecimal("100"));
            return c;
        }

        @Test
        void wholePositiveQuantity_isValid() {
            InterbankOtcContract c = base();
            c.setQuantity(new BigDecimal("100"));
            assertThat(c.isQuantityWholeShares()).isTrue();
        }

        @Test
        void wholeQuantityWithTrailingZeroScale_isValid() {
            // 100.0000 (scale 4 u koloni) ali ceo broj — stripTrailingZeros() => scale 0.
            InterbankOtcContract c = base();
            c.setQuantity(new BigDecimal("100.0000"));
            assertThat(c.isQuantityWholeShares()).isTrue();
        }

        @Test
        void fractionalQuantity_isRejected() {
            InterbankOtcContract c = base();
            c.setQuantity(new BigDecimal("100.5000"));

            // Entitet ima SAMO @AssertTrue bean-validaciju (kolone su JPA-level),
            // pa validacija default grupe pogadja tacno isQuantityWholeShares.
            Set<ConstraintViolation<InterbankOtcContract>> violations = validator.validate(c);
            assertThat(c.isQuantityWholeShares()).isFalse();
            assertThat(violations).anyMatch(v -> "quantityWholeShares".equals(v.getPropertyPath().toString()));
        }

        @Test
        void zeroQuantity_isRejected() {
            InterbankOtcContract c = base();
            c.setQuantity(BigDecimal.ZERO);
            assertThat(c.isQuantityWholeShares()).isFalse();
        }

        @Test
        void negativeQuantity_isRejected() {
            InterbankOtcContract c = base();
            c.setQuantity(new BigDecimal("-5"));
            assertThat(c.isQuantityWholeShares()).isFalse();
        }

        @Test
        void nullQuantity_passesAssertTrue_handledByNotNullColumn() {
            InterbankOtcContract c = base();
            c.setQuantity(null);
            // @AssertTrue propusta null (NotNull kolona ga hvata zasebno) da ne dupliramo poruke.
            assertThat(c.isQuantityWholeShares()).isTrue();
        }
    }

    @Nested
    class AccountSingleOwner_R4_1775 {

        private Account.AccountBuilder base() {
            return Account.builder().accountNumber("222000100000000010");
        }

        @Test
        void clientOnly_isValid() {
            Account a = base().client(new Client()).company(null).build();
            assertThat(a.isOwnerValid()).isTrue();
        }

        @Test
        void companyOnly_isValid() {
            Account a = base().client(null).company(new Company()).build();
            assertThat(a.isOwnerValid()).isTrue();
        }

        @Test
        void bothOwners_isInvalid() {
            Account a = base().client(new Client()).company(new Company()).build();
            assertThat(a.isOwnerValid()).isFalse();
        }

        @Test
        void noOwner_isInvalid() {
            Account a = base().client(null).company(null).build();
            assertThat(a.isOwnerValid()).isFalse();
        }
    }
}
