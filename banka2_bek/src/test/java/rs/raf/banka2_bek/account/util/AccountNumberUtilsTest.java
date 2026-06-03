package rs.raf.banka2_bek.account.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import rs.raf.banka2_bek.account.model.AccountSubtype;
import rs.raf.banka2_bek.account.model.AccountType;

import static org.junit.jupiter.api.Assertions.*;

class AccountNumberUtilsTest {

    @Test
    @DisplayName("generise 18-cifreni broj racuna")
    void generates18DigitAccountNumber() {
        String number = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.STANDARD, false);
        assertNotNull(number);
        assertEquals(18, number.length());
        assertTrue(number.matches("\\d{18}"));
    }

    @Test
    @DisplayName("pocinje sa bank code 222")
    void startsWithBankCode() {
        String number = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.STANDARD, false);
        assertTrue(number.startsWith("222"));
    }

    @Test
    @DisplayName("branch code je 0001")
    void hasBranchCode() {
        String number = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.STANDARD, false);
        assertEquals("0001", number.substring(3, 7));
    }

    @Test
    @DisplayName("R2 1416 - bank code je derivat maticnog broja (jedinstven izvor)")
    void bankCodeDerivedFromRegistrationNumber() {
        // Routing/bank code = prve 3 cifre maticnog broja; ne sme se razilaziti.
        assertEquals("222", AccountNumberUtils.BANK_CODE);
        assertEquals(AccountNumberUtils.BANK_REGISTRATION_NUMBER.substring(0, 3),
                AccountNumberUtils.BANK_CODE);
        // Mora se poklapati sa interbank.my-routing-number=222 (application.properties).
        assertTrue(AccountNumberUtils.BANK_REGISTRATION_NUMBER.startsWith("222"));
    }

    @Test
    @DisplayName("prolazi mod11 validaciju")
    void passesmod11() {
        for (int i = 0; i < 20; i++) {
            String number = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.STANDARD, false);
            int sum = 0;
            for (char c : number.toCharArray()) {
                sum += Character.getNumericValue(c);
            }
            assertEquals(0, sum % 11, "Mod11 failed for: " + number);
        }
    }

    @Test
    @DisplayName("tekuci standard zavrsava na 10")
    void checkingStandardEndsWith10() {
        String number = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.STANDARD, false);
        assertTrue(number.endsWith("10"), "Expected ending 10, got: " + number);
    }

    @Test
    @DisplayName("stedni zavrsava na 13")
    void savingsEndsWith13() {
        String number = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.SAVINGS, false);
        assertTrue(number.endsWith("13"), "Expected ending 13, got: " + number);
    }

    @Test
    @DisplayName("penzionerski zavrsava na 14")
    void pensionEndsWith14() {
        String number = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.PENSION, false);
        assertTrue(number.endsWith("14"), "Expected ending 14, got: " + number);
    }

    @Test
    @DisplayName("omladinski zavrsava na 15")
    void youthEndsWith15() {
        String number = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.YOUTH, false);
        assertTrue(number.endsWith("15"), "Expected ending 15, got: " + number);
    }

    @Test
    @DisplayName("studentski zavrsava na 16")
    void studentEndsWith16() {
        String number = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.STUDENT, false);
        assertTrue(number.endsWith("16"), "Expected ending 16, got: " + number);
    }

    @Test
    @DisplayName("nezaposleni zavrsava na 17")
    void unemployedEndsWith17() {
        String number = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.UNEMPLOYED, false);
        assertTrue(number.endsWith("17"), "Expected ending 17, got: " + number);
    }

    @Test
    @DisplayName("devizni licni zavrsava na 21")
    void foreignPersonalEndsWith21() {
        String number = AccountNumberUtils.generate(AccountType.FOREIGN, AccountSubtype.PERSONAL, false);
        assertTrue(number.endsWith("21"), "Expected ending 21, got: " + number);
    }

    @Test
    @DisplayName("devizni poslovni zavrsava na 22")
    void foreignBusinessEndsWith22() {
        String number = AccountNumberUtils.generate(AccountType.FOREIGN, AccountSubtype.PERSONAL, true);
        assertTrue(number.endsWith("22"), "Expected ending 22, got: " + number);
    }

    @Test
    @DisplayName("poslovni racun zavrsava na 12")
    void businessEndsWith12() {
        String number = AccountNumberUtils.generate(AccountType.BUSINESS, AccountSubtype.STANDARD, false);
        assertTrue(number.endsWith("12"), "Expected ending 12, got: " + number);
    }

    @Test
    @DisplayName("poslovni flag isBusiness=true zavrsava na 12")
    void isBusinessFlagEndsWith12() {
        String number = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.STANDARD, true);
        assertTrue(number.endsWith("12"), "Expected ending 12, got: " + number);
    }

    @Test
    @DisplayName("licni zavrsava na 11")
    void personalEndsWith11() {
        String number = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.PERSONAL, false);
        assertTrue(number.endsWith("11"), "Expected ending 11, got: " + number);
    }

    @Test
    @DisplayName("tekuci bez podtipa zavrsava na 10")
    void checkingNullSubtypeEndsWith10() {
        String number = AccountNumberUtils.generate(AccountType.CHECKING, null, false);
        assertTrue(number.endsWith("10"), "Expected ending 10, got: " + number);
    }

    @Test
    @DisplayName("R1-624: tekuci SALARY eksplicitno zavrsava na 10 (bez tihog default-a)")
    void checkingSalaryEndsWith10() {
        String number = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.SALARY, false);
        assertTrue(number.endsWith("10"), "Expected ending 10, got: " + number);
    }

    @Test
    @DisplayName("generise razlicite brojeve pri svakom pozivu")
    void generatesUniqueNumbers() {
        String n1 = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.STANDARD, false);
        String n2 = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.STANDARD, false);
        assertNotEquals(n1, n2);
    }
}
