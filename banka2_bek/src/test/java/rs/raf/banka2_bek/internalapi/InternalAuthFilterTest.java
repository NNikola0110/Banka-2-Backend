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

    // ─── H3: prefiks validnog kljuca se odbija (constant-time compare ne sme da
    //         prihvati skraceni kljuc) ──────────────────────────────────────────

    @Test
    void keyThatIsPrefixOfValidKey_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/internal/funds/reserve");
        // "test-internal" je prefiks "test-internal-key" — duzina se razlikuje;
        // MessageDigest.isEqual odbija (ne sme da prihvati skraceni kljuc).
        req.addHeader("X-Internal-Key", VALID_KEY.substring(0, VALID_KEY.length() - 4));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void wrongKeyOfSameLength_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/internal/funds/reserve");
        // Ista duzina kao VALID_KEY, drugaciji sadrzaj — constant-time compare
        // ga odbija (sva poredjenja moraju da prodju, bez kratkog spoja).
        String sameLengthWrong = "X".repeat(VALID_KEY.length());
        req.addHeader("X-Internal-Key", sameLengthWrong);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    // ─── TEST-branches-9: servlet-container auto-registracija filtera je ISKLJUCENA
    //     (FilterRegistrationBean.setEnabled(false)) da se filter NE izvrsava dvaput
    //     (jednom u servlet chain-u, jednom u Security chain-u). Merodavna je samo
    //     Security-chain registracija (gde filter postavi ROLE_INTERNAL pre
    //     AuthorizationFilter-a). R1-710. ──────────────────────────────────────────

    @Test
    void servletRegistrationOfInternalAuthFilterIsDisabled_TEST_branches_9() {
        // Bean metod koristi SAMO svoj parametar (ne dira ostala 3 filtera), pa je
        // bezbedno konstruisati GlobalSecurityConfig sa null-ovima za druge filtere.
        rs.raf.banka2_bek.auth.config.GlobalSecurityConfig config =
                new rs.raf.banka2_bek.auth.config.GlobalSecurityConfig(null, null, null, filter);

        org.springframework.boot.web.servlet.FilterRegistrationBean<InternalAuthFilter> reg =
                config.internalAuthFilterServletDisable(filter);

        // KLJUCNA invarijanta: servlet auto-registracija mora biti iskljucena
        // (inace dvostruko izvrsavanje filtera za /internal/** zahteve).
        assertThat(reg.isEnabled()).isFalse();
        assertThat(reg.getFilter()).isSameAs(filter);
    }
}
