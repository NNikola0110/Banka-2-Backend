package rs.raf.banka2_bek.branch.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R1-706 / R1-707: pinuje JPA {@code @PrePersist/@PreUpdate} invarijante na {@link Branch}:
 * WGS84 opsezi lat/lon + has24h/hasDriveThrough samo za ATM. Ranije su bile samo komentar.
 */
class BranchTest {

    private static void validate(Branch b) throws Exception {
        Method m = Branch.class.getDeclaredMethod("validateInvariants");
        m.setAccessible(true);
        try {
            m.invoke(b);
        } catch (InvocationTargetException e) {
            // odmotaj pravi exception iz refleksije
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    private static Branch.BranchBuilder validAtm() {
        return Branch.builder()
                .name("ATM Test").type(BranchType.ATM).address("Adresa 1")
                .latitude(BigDecimal.valueOf(44.8))
                .longitude(BigDecimal.valueOf(20.45))
                .openingHours("00-24")
                .has24h(true).hasDriveThrough(true);
    }

    private static Branch.BranchBuilder validBranch() {
        return Branch.builder()
                .name("Ekspozitura Test").type(BranchType.BRANCH).address("Adresa 2")
                .latitude(BigDecimal.valueOf(44.8))
                .longitude(BigDecimal.valueOf(20.45))
                .openingHours("08-16")
                .has24h(false).hasDriveThrough(false);
    }

    @Test
    @DisplayName("validne ATM i BRANCH lokacije prolaze validaciju")
    void validLocations_pass() {
        assertThatCode(() -> validate(validAtm().build())).doesNotThrowAnyException();
        assertThatCode(() -> validate(validBranch().build())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("R1-706: latitude van [-90,90] → IllegalState")
    void latitudeOutOfRange_throws() {
        assertThatThrownBy(() -> validate(validAtm().latitude(BigDecimal.valueOf(91)).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("latitude");
    }

    @Test
    @DisplayName("R1-706: longitude van [-180,180] → IllegalState")
    void longitudeOutOfRange_throws() {
        assertThatThrownBy(() -> validate(validAtm().longitude(BigDecimal.valueOf(-181)).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("longitude");
    }

    @Test
    @DisplayName("R1-707: BRANCH sa has24h=true → IllegalState (samo ATM moze)")
    void branchWith24h_throws() {
        assertThatThrownBy(() -> validate(validBranch().has24h(true).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ATM");
    }

    @Test
    @DisplayName("R1-707: BRANCH sa hasDriveThrough=true → IllegalState (samo ATM moze)")
    void branchWithDriveThrough_throws() {
        Throwable t = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> validate(validBranch().hasDriveThrough(true).build()));
        assertThat(t.getMessage()).contains("ATM");
    }
}
