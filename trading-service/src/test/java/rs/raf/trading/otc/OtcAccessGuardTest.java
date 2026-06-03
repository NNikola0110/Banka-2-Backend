package rs.raf.trading.otc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.otc.service.OtcAccessGuard;
import rs.raf.trading.security.TradingUserResolver;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;

/**
 * P1-authz-idor-1 — testovi zajednickog OTC IDOR guard-a.
 */
@ExtendWith(MockitoExtension.class)
class OtcAccessGuardTest {

    @Mock
    private TradingUserResolver userResolver;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private OtcAccessGuard guard() {
        return new OtcAccessGuard(userResolver);
    }

    @Test
    void buyer_isAllowed() {
        authenticateAs("CLIENT");
        lenient().when(userResolver.resolveCurrent()).thenReturn(new UserContext(7L, "CLIENT"));
        assertThatCode(() -> guard().ensureParticipantOrOversight(
                7L, "CLIENT", 8L, "CLIENT", "saga")).doesNotThrowAnyException();
    }

    @Test
    void seller_isAllowed() {
        authenticateAs("CLIENT");
        lenient().when(userResolver.resolveCurrent()).thenReturn(new UserContext(8L, "CLIENT"));
        assertThatCode(() -> guard().ensureParticipantOrOversight(
                7L, "CLIENT", 8L, "CLIENT", "saga")).doesNotThrowAnyException();
    }

    @Test
    void nonParticipantClient_isDenied() {
        authenticateAs("CLIENT");
        lenient().when(userResolver.resolveCurrent()).thenReturn(new UserContext(99L, "CLIENT"));
        assertThatThrownBy(() -> guard().ensureParticipantOrOversight(
                7L, "CLIENT", 8L, "CLIENT", "saga"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void sameIdDifferentRole_isDenied() {
        // Employee #7 ne sme da cita resurs klijenta #7 (id se poklapa, rola ne).
        authenticateAs("EMPLOYEE");
        lenient().when(userResolver.resolveCurrent()).thenReturn(new UserContext(7L, "EMPLOYEE"));
        assertThatThrownBy(() -> guard().ensureParticipantOrOversight(
                7L, "CLIENT", 8L, "CLIENT", "saga"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminOversight_bypassesParticipantCheck() {
        authenticateAs("ADMIN");
        // resolveCurrent se ne dira jer oversight kratko-spaja proveru
        assertThatCode(() -> guard().ensureParticipantOrOversight(
                7L, "CLIENT", 8L, "CLIENT", "saga")).doesNotThrowAnyException();
    }

    @Test
    void supervisorOversight_bypassesParticipantCheck() {
        authenticateAs("SUPERVISOR");
        assertThatCode(() -> guard().ensureParticipantOrOversight(
                7L, "CLIENT", 8L, "CLIENT", "saga")).doesNotThrowAnyException();
    }

    @Test
    void isAdminOrSupervisor_reflectsAuthorities() {
        authenticateAs("SUPERVISOR");
        assertThat(guard().isAdminOrSupervisor()).isTrue();
        authenticateAs("CLIENT");
        assertThat(guard().isAdminOrSupervisor()).isFalse();
        authenticateAs("ROLE_ADMIN");
        assertThat(guard().isAdminOrSupervisor()).isTrue();
    }

    private void authenticateAs(String... authorities) {
        SecurityContext ctx = new SecurityContextImpl();
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                "user", "pwd",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        ctx.setAuthentication(token);
        SecurityContextHolder.setContext(ctx);
    }
}
