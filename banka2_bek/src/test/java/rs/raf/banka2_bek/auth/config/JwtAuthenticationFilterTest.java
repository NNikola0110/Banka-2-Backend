package rs.raf.banka2_bek.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import rs.raf.banka2_bek.auth.service.CustomUserDetailsService;
import rs.raf.banka2_bek.auth.service.JwtBlacklistService;
import rs.raf.banka2_bek.auth.service.JwtService;

import java.io.IOException;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private CustomUserDetailsService userDetailsService;

    @Mock
    private JwtBlacklistService blacklistService;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_noAuthHeader_continuesChain() throws ServletException, IOException {
        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilterInternal_nonBearerHeader_continuesChain() throws ServletException, IOException {
        request.addHeader("Authorization", "Basic abc123");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilterInternal_validToken_setsAuthentication() throws ServletException, IOException {
        String token = "valid-jwt-token";
        request.addHeader("Authorization", "Bearer " + token);

        UserDetails userDetails = new User("marko@banka.rs", "password", Collections.emptyList());

        when(jwtService.extractEmail(token)).thenReturn("marko@banka.rs");
        when(userDetailsService.loadUserByUsername("marko@banka.rs")).thenReturn(userDetails);
        when(jwtService.isTokenValid(token, userDetails)).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("marko@banka.rs");
    }

    @Test
    void doFilterInternal_invalidToken_doesNotSetAuthentication() throws ServletException, IOException {
        String token = "invalid-jwt-token";
        request.addHeader("Authorization", "Bearer " + token);

        UserDetails userDetails = new User("marko@banka.rs", "password", Collections.emptyList());

        when(jwtService.extractEmail(token)).thenReturn("marko@banka.rs");
        when(userDetailsService.loadUserByUsername("marko@banka.rs")).thenReturn(userDetails);
        when(jwtService.isTokenValid(token, userDetails)).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilterInternal_extractEmailReturnsNull_continuesChain() throws ServletException, IOException {
        String token = "some-token";
        request.addHeader("Authorization", "Bearer " + token);

        when(jwtService.extractEmail(token)).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilterInternal_exceptionThrown_returns401() throws ServletException, IOException {
        String token = "bad-token";
        request.addHeader("Authorization", "Bearer " + token);

        when(jwtService.extractEmail(token)).thenThrow(new RuntimeException("Token parse error"));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void doFilterInternal_disabledAccount_returns401_doesNotSetAuthentication()
            throws ServletException, IOException {
        // N1: deaktiviran (disabled) nalog sa validno potpisanim access tokenom NE sme
        // da zadrzi pristup. Filter mora odbiti sa 401 i NE postaviti Authentication.
        String token = "valid-but-disabled";
        request.addHeader("Authorization", "Bearer " + token);

        UserDetails disabled = User.builder()
                .username("marko@banka.rs")
                .password("password")
                .authorities(Collections.emptyList())
                .disabled(true)
                .build();

        when(jwtService.extractEmail(token)).thenReturn("marko@banka.rs");
        when(userDetailsService.loadUserByUsername("marko@banka.rs")).thenReturn(disabled);
        // N1: enabled/locked guard je PRE isTokenValid — isTokenValid se ne dosegne.

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilterInternal_lockedAccount_returns401_doesNotSetAuthentication()
            throws ServletException, IOException {
        // N1: zakljucan (accountLocked) nalog sa validnim tokenom mora dobiti 401.
        String token = "valid-but-locked";
        request.addHeader("Authorization", "Bearer " + token);

        UserDetails locked = User.builder()
                .username("marko@banka.rs")
                .password("password")
                .authorities(Collections.emptyList())
                .accountLocked(true)
                .build();

        when(jwtService.extractEmail(token)).thenReturn("marko@banka.rs");
        when(userDetailsService.loadUserByUsername("marko@banka.rs")).thenReturn(locked);
        // N1: enabled/locked guard je PRE isTokenValid — isTokenValid se ne dosegne.

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilterInternal_refreshTokenAsBearer_returns401() throws ServletException, IOException {
        // SEC-03: refresh token ne sme da prodje kao access token kroz Bearer
        // header. Filter mora vratiti 401 i NE zvati doFilter za downstream.
        String refreshToken = "refresh.jwt.token";
        request.addHeader("Authorization", "Bearer " + refreshToken);

        when(jwtService.isRefreshToken(refreshToken)).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(request, response);
        // extractEmail i loadUserByUsername se NE smeju zvati za refresh tokene.
        verify(jwtService, never()).extractEmail(anyString());
    }
}
