package rs.raf.banka2_bek.interbank.wrapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.raf.banka2_bek.auth.util.UserContext;
import rs.raf.banka2_bek.auth.util.UserResolver;
import rs.raf.banka2_bek.interbank.wrapper.InterbankOtcWrapperDtos.InterbankTransactionDto;

import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-9 — MockMvc tests for GET /interbank/payments/{id}. Verifies the endpoint is
 * reachable with a JWT (no X-Api-Key), returns the mapped view, and that the
 * ROLLED_BACK→ABORTED status mapping reaches the FE shape.
 */
@ExtendWith(MockitoExtension.class)
class InterbankPaymentQueryControllerTest {

    @Mock private InterbankOtcWrapperService wrapperService;
    @Mock private UserResolver userResolver;

    private MockMvc mockMvc;

    private static final Long USER_ID = 7L;

    @BeforeEach
    void setUp() {
        InterbankPaymentQueryController controller =
                new InterbankPaymentQueryController(wrapperService, userResolver);

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(converter)
                .setControllerAdvice(new InterbankPaymentQueryController.ExceptionMapping())
                .build();

        lenient().when(userResolver.resolveCurrent())
                .thenReturn(new UserContext(USER_ID, "CLIENT"));
    }

    @Test
    @DisplayName("GET /interbank/payments/{id} → 200 + mapped view with ABORTED (ROLLED_BACK→ABORTED) (P1-9)")
    void getInterbankTransaction_abortedView() throws Exception {
        // Service maps an InterbankTransaction in ROLLED_BACK to status=ABORTED.
        InterbankTransactionDto view = new InterbankTransactionDto(
                42L, "tx-id-1", "PAYMENT", "ABORTED", null,
                "RN-222", "111900001",
                java.math.BigDecimal.valueOf(500), "RSD",
                null, null, null,
                null, null, null, 0,
                "Banka primaoca je odbacila transakciju.");
        when(wrapperService.getInterbankTransactionView(eq("tx-id-1"), eq(USER_ID), eq("CLIENT")))
                .thenReturn(view);

        mockMvc.perform(get("/interbank/payments/{id}", "tx-id-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.transactionId").value("tx-id-1"))
                .andExpect(jsonPath("$.status").value("ABORTED"))
                .andExpect(jsonPath("$.failureReason").value("Banka primaoca je odbacila transakciju."));
    }

    @Test
    @DisplayName("GET /interbank/payments/{id} → 200 + COMMITTED view for OTC contract (P1-9)")
    void getInterbankTransaction_committedView() throws Exception {
        InterbankTransactionDto view = new InterbankTransactionDto(
                10L, "10", "OTC", "COMMITTED", "Finalizacija",
                "RN-111", "RN-222",
                java.math.BigDecimal.valueOf(1500), "USD",
                "AAPL", java.math.BigDecimal.TEN, java.math.BigDecimal.valueOf(150),
                null, null, null, 0, null);
        when(wrapperService.getInterbankTransactionView(eq("10"), eq(USER_ID), eq("CLIENT")))
                .thenReturn(view);

        mockMvc.perform(get("/interbank/payments/{id}", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.currentPhase").value("Finalizacija"))
                .andExpect(jsonPath("$.listingTicker").value("AAPL"));
    }

    @Test
    @DisplayName("GET /interbank/payments/{id} → 404 when service throws NoSuchElement (P1-9)")
    void getInterbankTransaction_notFound() throws Exception {
        when(wrapperService.getInterbankTransactionView(any(), any(), any()))
                .thenThrow(new NoSuchElementException("Transakcija nije pronadjena."));

        mockMvc.perform(get("/interbank/payments/{id}", "999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /interbank/payments/{id} → 403 when caller does not own the resource (P1-9)")
    void getInterbankTransaction_forbidden() throws Exception {
        when(wrapperService.getInterbankTransactionView(any(), any(), any()))
                .thenThrow(new AccessDeniedException("Ne pripada korisniku."));

        mockMvc.perform(get("/interbank/payments/{id}", "5"))
                .andExpect(status().isForbidden());
    }
}
