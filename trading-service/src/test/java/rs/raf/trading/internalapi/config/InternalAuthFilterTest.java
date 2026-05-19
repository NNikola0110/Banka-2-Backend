package rs.raf.trading.internalapi.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit testovi za trading-service {@link InternalAuthFilter} — X-Internal-Key
 * zastita {@code /internal/**} ruta (faza 2f).
 */
class InternalAuthFilterTest {

    private static final String VALID_KEY = "test-internal-key";

    private InternalAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new InternalAuthFilter(VALID_KEY);
        SecurityContextHolder.clearContext();
    }

    @Test
    void nonInternalPath_filterIsSkipped() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/portfolio/my");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void validKey_callsChainAndSetsRoleInternal() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/internal/portfolio/reserve-stock");
        req.addHeader("X-Internal-Key", VALID_KEY);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo("internal-service");
        assertThat(auth.getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_INTERNAL"));
    }

    @Test
    void missingKey_returns401AndChainNotCalled() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/internal/portfolio/commit-stock");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(res.getContentAsString()).contains("UNAUTHORIZED");
    }

    @Test
    void wrongKey_returns401AndChainNotCalled() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/internal/portfolio/public-stock");
        req.addHeader("X-Internal-Key", "completely-wrong-key");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void blankKey_returns401AndChainNotCalled() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/internal/portfolio/release-stock");
        req.addHeader("X-Internal-Key", "   ");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }
}
