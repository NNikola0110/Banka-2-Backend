package rs.raf.banka2_bek.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * P1-auth-2 (R2 1366 + 1367): rate-limit pokrivenost novih path-ova (confirm/resend)
 * + XFF-spoof zastita (default trust-xff=false -> remoteAddr).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthRateLimitFilter — P1-auth-2 (XFF spoof + confirm/resend)")
class AuthRateLimitFilterTest {

    @Mock
    private FilterChain chain;

    @Mock
    private rs.raf.banka2_bek.monitoring.BusinessMetrics businessMetrics;

    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        response = new MockHttpServletResponse();
    }

    private MockHttpServletRequest postTo(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("POST");
        req.setRequestURI(uri);
        req.setRemoteAddr("10.0.0.1");
        return req;
    }

    @Test
    @DisplayName("1367: kad trust-xff=false, spoofovan X-Forwarded-For NE daje fresh bucket — limit se postuje")
    void xffSpoofDoesNotBypassWhenNotTrusted() throws ServletException, IOException {
        // capacity=2, trust-xff=false (default). Napadac menja XFF po zahtevu da dobije
        // svez bucket — ali filter koristi remoteAddr (isti za sve), pa 3. zahtev = 429.
        AuthRateLimitFilter filter = new AuthRateLimitFilter(2, false, businessMetrics);

        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = postTo("/auth/login");
            req.addHeader("X-Forwarded-For", "1.2.3." + i); // spoof: razlicit po zahtevu
            filter.doFilterInternal(req, new MockHttpServletResponse(), chain);
        }
        // 3. zahtev — i dalje isti remoteAddr bucket -> 429.
        MockHttpServletRequest third = postTo("/auth/login");
        third.addHeader("X-Forwarded-For", "9.9.9.9");
        filter.doFilterInternal(third, response, chain);

        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("1367: kad trust-xff=true (iza proxy-ja), razlicit XFF = razlicit bucket (legitiman per-client)")
    void xffTrustedGivesPerClientBucketBehindProxy() throws ServletException, IOException {
        // capacity=1, trust-xff=true. Dva razlicita klijenta iza proxy-ja (razlicit XFF)
        // dobijaju nezavisne bucket-e -> oba prolaze prvi zahtev.
        AuthRateLimitFilter filter = new AuthRateLimitFilter(1, true, businessMetrics);

        MockHttpServletRequest clientA = postTo("/auth/login");
        clientA.addHeader("X-Forwarded-For", "203.0.113.1");
        filter.doFilterInternal(clientA, new MockHttpServletResponse(), chain);

        MockHttpServletRequest clientB = postTo("/auth/login");
        clientB.addHeader("X-Forwarded-For", "203.0.113.2");
        MockHttpServletResponse respB = new MockHttpServletResponse();
        filter.doFilterInternal(clientB, respB, chain);

        // Oba prosla (razliciti XFF bucket-i), nijedan 429.
        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(respB.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("1366: /auth/password_reset/confirm je rate-limit-ovan (brute-force reset tokena)")
    void confirmIsRateLimited() throws ServletException, IOException {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(1, false, businessMetrics);

        filter.doFilterInternal(postTo("/auth/password_reset/confirm"), new MockHttpServletResponse(), chain);
        filter.doFilterInternal(postTo("/auth/password_reset/confirm"), response, chain);

        verify(chain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        // R7 observability: 429 inkrementira banka2_rate_limit_hit_total (RateLimitFloodActive alert).
        verify(businessMetrics).recordRateLimitHit();
    }

    @Test
    @DisplayName("1366: /auth-employee/resend-activation je rate-limit-ovan (email bombing)")
    void resendActivationIsRateLimited() throws ServletException, IOException {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(1, false, businessMetrics);

        filter.doFilterInternal(postTo("/auth-employee/resend-activation"), new MockHttpServletResponse(), chain);
        filter.doFilterInternal(postTo("/auth-employee/resend-activation"), response, chain);

        verify(chain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("Ne-rate-limit-ovan path prolazi neograniceno")
    void unrelatedPathNotLimited() throws ServletException, IOException {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(1, false, businessMetrics);

        for (int i = 0; i < 5; i++) {
            filter.doFilterInternal(postTo("/payments"), new MockHttpServletResponse(), chain);
        }

        verify(chain, times(5)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
