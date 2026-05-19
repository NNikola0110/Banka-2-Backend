package rs.raf.trading.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.raf.banka2.contracts.internal.InternalOtpVerifyResponse;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.TradingGlobalExceptionHandler;
import rs.raf.trading.order.controller.exception_handler.OrderExceptionHandler;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.dto.OrderDto;
import rs.raf.trading.order.service.OrderService;

import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test {@link OrderController} POST /orders — adaptacija monolitnog testa
 * (faza 2c). OTP verifikacija ide preko {@link BankaCoreClient#verifyOtp}
 * (umesto monolitnog {@code OtpService}); exception handling kroz
 * {@link TradingGlobalExceptionHandler} + {@link OrderExceptionHandler}.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("OrderController — POST /orders")
class OrderControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Mock
    private OrderService orderService;

    @Mock
    private BankaCoreClient bankaCoreClient;

    @InjectMocks
    private OrderController orderController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(orderController)
                .setControllerAdvice(new OrderExceptionHandler(), new TradingGlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test@example.com", null));
        // OTP verify uvek uspesan u ovim testovima — OTP gate je pokriven OrderControllerOtpTest-om.
        when(bankaCoreClient.verifyOtp(anyString(), anyString()))
                .thenReturn(new InternalOtpVerifyResponse(true, false));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private OrderDto sampleOrderDto() {
        OrderDto dto = new OrderDto();
        dto.setId(1L);
        dto.setListingId(1L);
        dto.setListingTicker("AAPL");
        dto.setOrderType("MARKET");
        dto.setDirection("BUY");
        dto.setQuantity(5);
        dto.setContractSize(1);
        dto.setPricePerUnit(new BigDecimal("151.00"));
        dto.setApproximatePrice(new BigDecimal("755.0000"));
        dto.setStatus("APPROVED");
        dto.setApprovedBy("No need for approval");
        dto.setDone(false);
        dto.setRemainingPortions(5);
        dto.setAfterHours(false);
        dto.setAllOrNone(false);
        dto.setMargin(false);
        dto.setUserRole("CLIENT");
        dto.setCreatedAt(LocalDateTime.now());
        dto.setLastModification(LocalDateTime.now());
        return dto;
    }

    private String validMarketBuyJson() throws Exception {
        CreateOrderDto dto = new CreateOrderDto();
        dto.setListingId(1L);
        dto.setOrderType("MARKET");
        dto.setDirection("BUY");
        dto.setQuantity(5);
        dto.setContractSize(1);
        dto.setAccountId(100L);
        dto.setOtpCode("123456");
        return objectMapper.writeValueAsString(dto);
    }

    @Nested
    @DisplayName("200 OK — validni zahtev")
    class SuccessCases {

        @Test
        @DisplayName("POST /orders sa validnim MARKET BUY → 200 sa OrderDto")
        void validMarketBuyReturns200() throws Exception {
            when(orderService.createOrder(any())).thenReturn(sampleOrderDto());

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validMarketBuyJson()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.listingTicker").value("AAPL"))
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.approvedBy").value("No need for approval"))
                    .andExpect(jsonPath("$.orderType").value("MARKET"))
                    .andExpect(jsonPath("$.direction").value("BUY"))
                    .andExpect(jsonPath("$.quantity").value(5))
                    .andExpect(jsonPath("$.done").value(false))
                    .andExpect(jsonPath("$.remainingPortions").value(5));
        }

        @Test
        @DisplayName("PENDING order — status=PENDING, approvedBy=null")
        void pendingOrderReturns200WithPendingStatus() throws Exception {
            OrderDto pendingDto = sampleOrderDto();
            pendingDto.setStatus("PENDING");
            pendingDto.setApprovedBy(null);
            when(orderService.createOrder(any())).thenReturn(pendingDto);

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validMarketBuyJson()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.approvedBy").doesNotExist());
        }
    }

    @Nested
    @DisplayName("400 — validacione greške")
    class ValidationErrors {

        @Test
        @DisplayName("Nedostaje quantity → 400")
        void missingQuantityReturns400() throws Exception {
            String json = """
                    {
                      "listingId": 1,
                      "orderType": "MARKET",
                      "direction": "BUY",
                      "contractSize": 1,
                      "accountId": 100
                    }
                    """;

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("contractSize je opciono polje sa default vrednošću 1 → prolazi validaciju")
        void missingContractSizeDefaultsTo1() throws Exception {
            String json = """
                    {
                      "listingId": 1,
                      "orderType": "MARKET",
                      "direction": "BUY",
                      "quantity": 5,
                      "accountId": 100,
                      "otpCode": "123456"
                    }
                    """;

            when(orderService.createOrder(any())).thenReturn(sampleOrderDto());
            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Nedostaje accountId → 400")
        void missingAccountIdReturns400() throws Exception {
            String json = """
                    {
                      "listingId": 1,
                      "orderType": "MARKET",
                      "direction": "BUY",
                      "quantity": 5,
                      "contractSize": 1
                    }
                    """;

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Servisna greška — nevalidan orderType → 400")
        void invalidOrderTypeFromServiceReturns400() throws Exception {
            when(orderService.createOrder(any()))
                    .thenThrow(new IllegalArgumentException("Invalid order type or direction"));

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validMarketBuyJson()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Invalid order type or direction"));
        }

        @Test
        @DisplayName("Insufficient funds → 400")
        void insufficientFundsReturns400() throws Exception {
            when(orderService.createOrder(any()))
                    .thenThrow(new IllegalArgumentException("Insufficient funds"));

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validMarketBuyJson()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Insufficient funds"));
        }

        @Test
        @DisplayName("Limit value nedostaje → 400")
        void limitValueMissingReturns400() throws Exception {
            when(orderService.createOrder(any()))
                    .thenThrow(new IllegalArgumentException("Limit value is required for LIMIT and STOP_LIMIT orders"));

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validMarketBuyJson()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Limit value is required for LIMIT and STOP_LIMIT orders"));
        }

        @Test
        @DisplayName("Stop value nedostaje → 400")
        void stopValueMissingReturns400() throws Exception {
            when(orderService.createOrder(any()))
                    .thenThrow(new IllegalArgumentException("Stop value is required for STOP and STOP_LIMIT orders"));

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validMarketBuyJson()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Stop value is required for STOP and STOP_LIMIT orders"));
        }
    }

    @Nested
    @DisplayName("404 — Listing ne postoji")
    class NotFoundCases {

        @Test
        @DisplayName("Listing not found → 404")
        void listingNotFoundReturns404() throws Exception {
            when(orderService.createOrder(any()))
                    .thenThrow(new EntityNotFoundException("Listing not found"));

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validMarketBuyJson()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Listing not found"));
        }
    }
}
