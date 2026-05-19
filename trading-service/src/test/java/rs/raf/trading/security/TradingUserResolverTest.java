package rs.raf.trading.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testovi za TradingUserResolver — mockovan BankaCoreClient + stubiran
 * SecurityContext. Pokriva razresavanje trenutnog korisnika, kes (drugi poziv
 * ne pogadja klijent), rezoluciju imena + "Unknown" fallback i slucaj bez
 * autentifikacije.
 */
class TradingUserResolverTest {

    private BankaCoreClient bankaCoreClient;
    private TradingUserResolver resolver;

    @BeforeEach
    void setUp() {
        bankaCoreClient = mock(BankaCoreClient.class);
        resolver = new TradingUserResolver(bankaCoreClient);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String email) {
        var auth = new UsernamePasswordAuthenticationToken(email, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void resolveCurrent_clientUser_returnsUserContext() {
        authenticateAs("stefan.jovanovic@gmail.com");
        when(bankaCoreClient.getUserByEmail("stefan.jovanovic@gmail.com"))
                .thenReturn(new InternalUserDto(7L, "CLIENT", "stefan.jovanovic@gmail.com",
                        "Stefan", "Jovanovic", true));

        UserContext result = resolver.resolveCurrent();

        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.userRole()).isEqualTo("CLIENT");
        assertThat(result.isClient()).isTrue();
        assertThat(result.isEmployee()).isFalse();
    }

    @Test
    void resolveCurrent_employeeUser_returnsUserContext() {
        authenticateAs("tamara.pavlovic@banka.rs");
        when(bankaCoreClient.getUserByEmail("tamara.pavlovic@banka.rs"))
                .thenReturn(new InternalUserDto(3L, "EMPLOYEE", "tamara.pavlovic@banka.rs",
                        "Tamara", "Pavlovic", true));

        UserContext result = resolver.resolveCurrent();

        assertThat(result.userId()).isEqualTo(3L);
        assertThat(result.userRole()).isEqualTo("EMPLOYEE");
        assertThat(result.isEmployee()).isTrue();
        assertThat(result.isClient()).isFalse();
    }

    @Test
    void resolveCurrent_cachesByEmail_secondCallDoesNotReHitClient() {
        authenticateAs("stefan.jovanovic@gmail.com");
        when(bankaCoreClient.getUserByEmail("stefan.jovanovic@gmail.com"))
                .thenReturn(new InternalUserDto(7L, "CLIENT", "stefan.jovanovic@gmail.com",
                        "Stefan", "Jovanovic", true));

        UserContext first = resolver.resolveCurrent();
        UserContext second = resolver.resolveCurrent();

        assertThat(first).isEqualTo(second);
        // Caffeine kes: banka-core pogodjen tacno jednom za isti email.
        verify(bankaCoreClient, times(1)).getUserByEmail("stefan.jovanovic@gmail.com");
    }

    @Test
    void resolveCurrent_noAuthentication_throwsIllegalStateException() {
        // SecurityContext je prazan (clearContext u @AfterEach garantuje izolaciju).
        assertThatThrownBy(() -> resolver.resolveCurrent())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nema autentifikovanog korisnika");
    }

    @Test
    void resolveCurrent_bankaCoreFails_throwsIllegalStateException() {
        authenticateAs("missing@example.com");
        when(bankaCoreClient.getUserByEmail("missing@example.com"))
                .thenThrow(new BankaCoreClientException(404, "not found"));

        assertThatThrownBy(() -> resolver.resolveCurrent())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing@example.com");
    }

    @Test
    void resolveName_happyPath_returnsFirstAndLastName() {
        when(bankaCoreClient.getUserById("CLIENT", 7L))
                .thenReturn(new InternalUserDto(7L, "CLIENT", "stefan.jovanovic@gmail.com",
                        "Stefan", "Jovanovic", true));

        String name = resolver.resolveName(7L, "CLIENT");

        assertThat(name).isEqualTo("Stefan Jovanovic");
    }

    @Test
    void resolveName_clientNotFound_returnsUnknown() {
        when(bankaCoreClient.getUserById("CLIENT", 999L))
                .thenThrow(new BankaCoreClientException(404, "not found"));

        String name = resolver.resolveName(999L, "CLIENT");

        assertThat(name).isEqualTo("Unknown");
    }

    @Test
    void resolveName_nullArguments_returnsUnknownWithoutHittingClient() {
        assertThat(resolver.resolveName(null, "CLIENT")).isEqualTo("Unknown");
        assertThat(resolver.resolveName(7L, null)).isEqualTo("Unknown");

        verify(bankaCoreClient, times(0)).getUserById(anyString(), anyLong());
    }

    @Test
    void resolveName_cachesByRoleAndId_secondCallDoesNotReHitClient() {
        when(bankaCoreClient.getUserById("EMPLOYEE", 3L))
                .thenReturn(new InternalUserDto(3L, "EMPLOYEE", "tamara.pavlovic@banka.rs",
                        "Tamara", "Pavlovic", true));

        String first = resolver.resolveName(3L, "EMPLOYEE");
        String second = resolver.resolveName(3L, "EMPLOYEE");

        assertThat(first).isEqualTo("Tamara Pavlovic");
        assertThat(second).isEqualTo("Tamara Pavlovic");
        verify(bankaCoreClient, times(1)).getUserById("EMPLOYEE", 3L);
    }
}
