package rs.raf.banka2_bek.internalapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.banka2_bek.internalapi.config.InternalAuthFilter;

import static org.assertj.core.api.Assertions.assertThat;

class InternalAuthFilterTest {

    private static final String VALID_KEY = "test-internal-key";

    private InternalAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new InternalAuthFilter(VALID_KEY);
        SecurityContextHolder.clearContext();
    }

    // ─── shouldNotFilter (via public doFilter — filter skips non-/internal/ paths) ──

    @Test
    void nonInternalPath_filterIsSkipped() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/api/accounts");
        // No X-Internal-Key at all — if filter runs it would 401
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        // 200 (filter skipped → chain called through)
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull(); // chain was invoked
    }

    @Test
    void rootPath_filterIsSkipped() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    // ─── Valid key → chain called + ROLE_INTERNAL set ────────────────────────

    @Test
    void validKey_callsChainAndSetsRoleInternal() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/internal/funds/reserve");
        req.addHeader("X-Internal-Key", VALID_KEY);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        // Chain was invoked (not blocked)
        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);

        // ROLE_INTERNAL authority set in SecurityContext
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo("internal-service");
        assertThat(auth.getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_INTERNAL"));
    }

    // ─── Missing key → 401, chain NOT called ─────────────────────────────────

    @Test
    void missingKey_returns401AndChainNotCalled() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/internal/funds/reserve");
        // No header added
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull(); // chain NOT called
        assertThat(res.getContentAsString()).contains("UNAUTHORIZED");
    }

    // ─── Wrong key → 401, chain NOT called ───────────────────────────────────

    @Test
    void wrongKey_returns401AndChainNotCalled() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/internal/accounts/1");
        req.addHeader("X-Internal-Key", "completely-wrong-key");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    // ─── Blank key → 401, chain NOT called ───────────────────────────────────

    @Test
    void blankKey_returns401AndChainNotCalled() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/internal/funds/transfer");
        req.addHeader("X-Internal-Key", "   ");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }
}
