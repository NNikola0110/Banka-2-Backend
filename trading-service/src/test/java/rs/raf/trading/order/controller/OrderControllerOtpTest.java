package rs.raf.trading.order.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.banka2.contracts.internal.InternalOtpVerifyResponse;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.dto.OrderDto;
import rs.raf.trading.order.service.OrderService;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit testovi za OTP integraciju u {@link OrderController#createOrder} —
 * adaptacija monolitnog testa (faza 2c). Monolit je verifikovao OTP preko
 * lokalnog {@code OtpService}; trading-service ide preko banka-core internog
 * seam-a ({@link BankaCoreClient#verifyOtp} -> {@link InternalOtpVerifyResponse}).
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerOtpTest {

    @Mock
    private OrderService orderService;

    @Mock
    private BankaCoreClient bankaCoreClient;

    @InjectMocks
    private OrderController orderController;

    private static final String EMAIL = "stefan.jovanovic@gmail.com";

    @BeforeEach
    void setUp() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                EMAIL, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private CreateOrderDto validDto() {
        CreateOrderDto dto = new CreateOrderDto();
        dto.setListingId(1L);
        dto.setOrderType("MARKET");
        dto.setQuantity(5);
        dto.setContractSize(1);
        dto.setDirection("BUY");
        dto.setAccountId(10L);
        dto.setOtpCode("123456");
        return dto;
    }

    @Test
    void createOrder_withInvalidOtp_returns403() {
        CreateOrderDto dto = validDto();
        when(bankaCoreClient.verifyOtp(eq(EMAIL), eq("123456")))
                .thenReturn(new InternalOtpVerifyResponse(false, false));

        ResponseEntity<?> response = orderController.createOrder(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("verified")).isEqualTo(false);
        assertThat(body.get("error")).isEqualTo("Verifikacija neuspesna");
        verify(orderService, never()).createOrder(any());
    }

    @Test
    void createOrder_withBlockedOtp_returns403_withBlockedMessage() {
        CreateOrderDto dto = validDto();
        when(bankaCoreClient.verifyOtp(eq(EMAIL), eq("123456")))
                .thenReturn(new InternalOtpVerifyResponse(false, true));

        ResponseEntity<?> response = orderController.createOrder(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("blocked")).isEqualTo(true);
        assertThat((String) body.get("message")).contains("blokirana");
        verify(orderService, never()).createOrder(any());
    }

    @Test
    void createOrder_withValidOtp_callsService_returns200() {
        CreateOrderDto dto = validDto();
        OrderDto created = new OrderDto();
        when(bankaCoreClient.verifyOtp(eq(EMAIL), eq("123456")))
                .thenReturn(new InternalOtpVerifyResponse(true, false));
        when(orderService.createOrder(any(CreateOrderDto.class))).thenReturn(created);

        ResponseEntity<?> response = orderController.createOrder(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(created);
        verify(orderService, times(1)).createOrder(dto);
        verify(bankaCoreClient, times(1)).verifyOtp(EMAIL, "123456");
    }

    @Test
    void createOrder_withMissingOtp_returns403AndDoesNotCallService() {
        CreateOrderDto dto = validDto();
        dto.setOtpCode("");
        when(bankaCoreClient.verifyOtp(eq(EMAIL), eq("")))
                .thenReturn(new InternalOtpVerifyResponse(false, false));

        ResponseEntity<?> response = orderController.createOrder(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(orderService, never()).createOrder(any());
    }

    @Test
    void createOrder_withNoAuthentication_returns401() {
        SecurityContextHolder.clearContext();
        CreateOrderDto dto = validDto();

        ResponseEntity<?> response = orderController.createOrder(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(bankaCoreClient);
        verifyNoInteractions(orderService);
    }
}
