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
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.dto.OrderDto;
import rs.raf.trading.order.service.OrderService;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit testovi za {@link OrderController#createOrder} bez OTP gate-a.
 *
 * <p><b>ACCEPTED-DEVIATION (user-directed 03.06):</b> OTP/verifikacioni kod je
 * UKLONJEN sa kreiranja ordera (berza/trading) — OTP ostaje ISKLJUCIVO na
 * money-out flow-ovima (placanja + transferi). Raniji testovi ovde su asertovali
 * "OTP required → 403 na missing/invalid code" preko {@code BankaCoreClient.verifyOtp};
 * sada asertuju da kreiranje ordera USPEVA BEZ koda i da kontroler vise NE radi
 * nikakvu OTP verifikaciju (nema {@code BankaCoreClient} dependency-ja).
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerOtpTest {

    @Mock
    private OrderService orderService;

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
        return dto;
    }

    @Test
    void createOrder_withoutOtpCode_succeeds_noVerification() {
        // ACCEPTED-DEVIATION (03.06): nema OTP koda → nalog se kreira direktno.
        CreateOrderDto dto = validDto();
        // NE postavljamo otpCode — OTP gate vise ne postoji.
        OrderDto created = new OrderDto();
        when(orderService.createOrder(any(CreateOrderDto.class))).thenReturn(created);

        ResponseEntity<?> response = orderController.createOrder(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(created);
        verify(orderService, times(1)).createOrder(dto);
    }

    @Test
    void createOrder_withOtpCodePresent_isIgnored_andSucceeds() {
        // Back-compat: FE sme da posalje otpCode, BE ga ignorise — i dalje uspeh.
        CreateOrderDto dto = validDto();
        dto.setOtpCode("123456");
        OrderDto created = new OrderDto();
        when(orderService.createOrder(any(CreateOrderDto.class))).thenReturn(created);

        ResponseEntity<?> response = orderController.createOrder(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(created);
        verify(orderService, times(1)).createOrder(dto);
    }

    @Test
    void createOrder_callsServiceDirectly_noOtpRoundTrip() {
        // Pin invariant: kontroler poziva orderService.createOrder direktno,
        // bez ikakvog OTP round-trip-a (nema vise BankaCoreClient dependency-ja).
        CreateOrderDto dto = validDto();
        when(orderService.createOrder(any(CreateOrderDto.class))).thenReturn(new OrderDto());

        orderController.createOrder(dto);

        verify(orderService, times(1)).createOrder(dto);
        verifyNoMoreInteractions(orderService);
    }

    @Test
    void createOrder_withNoAuthentication_returns401() {
        SecurityContextHolder.clearContext();
        CreateOrderDto dto = validDto();

        ResponseEntity<?> response = orderController.createOrder(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(orderService);
    }
}
