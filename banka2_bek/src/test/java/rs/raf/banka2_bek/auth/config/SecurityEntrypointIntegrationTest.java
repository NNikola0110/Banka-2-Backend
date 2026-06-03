package rs.raf.banka2_bek.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-error-contract-1: anonimni (ili istekao) zahtev na zasticenoj ruti MORA
 * dati 401 sa JSON {@code {"message":...}} telom, NE Spring-default 403-prazno.
 *
 * <p>Pre fix-a: bez {@code authenticationEntryPoint}-a Spring 6 vraca
 * {@code Http403ForbiddenEntryPoint} (403 prazno) za anonimni zahtev → FE
 * interceptor (refresh samo na 401) ne okida → korisnik izbacen iako ima
 * validan refresh token; Mobile dobija prazno telo → generic poruka.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityEntrypointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousOnAuthenticatedRoute_returns401NotEmpty403() throws Exception {
        // /clients/me je `authenticated()` — anoniman pristup pre fix-a = 403 prazno.
        mockMvc.perform(get("/clients/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void anonymousOnRoleRestrictedRoute_returns401() throws Exception {
        // /audit/** je ADMIN/SUPERVISOR — anoniman korisnik nije autentifikovan,
        // pa MORA biti 401 (entrypoint), ne 403. (403 bi bio za autentifikovanog-bez-role.)
        mockMvc.perform(get("/audit/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void anonymousOnExchangeRates_returns401_notPermitAll() throws Exception {
        // R1 364 (P2-authz-method-1) — RED pre fix-a: /exchange-rates je bio
        // permitAll → anoniman 200 (trosi eksternu Fixer/FX kvotu bez auth-a).
        // Sad authenticated() → anoniman 401 (entrypoint).
        mockMvc.perform(get("/exchange-rates"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void anonymousOnExchangeCalculate_returns401_notPermitAll() throws Exception {
        // R1 364 (P2-authz-method-1) — /exchange/calculate isto vise nije permitAll.
        mockMvc.perform(get("/exchange/calculate")
                        .param("amount", "100")
                        .param("toCurrency", "EUR"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }
}
