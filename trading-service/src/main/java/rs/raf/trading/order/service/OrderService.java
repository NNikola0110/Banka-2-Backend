package rs.raf.trading.order.service;

import org.springframework.data.domain.Page;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.dto.OrderDto;

import java.time.LocalDate;

public interface OrderService {

    /**
     * Kreiranje BUY ili SELL ordera.
     * Validacije:
     * 1. Da li korisnik ima dovoljno sredstava na racunu (za BUY)
     * 2. Da li korisnik poseduje dovoljno hartija (za SELL)
     * 3. Da li je agent presao dnevni limit -> status PENDING
     * 4. Da li agent ima needApproval flag -> status PENDING
     * 5. Klijentovi orderi su automatski APPROVED
     * 6. Izracunati approximatePrice = contractSize * pricePerUnit * quantity
     * 7. Provizija: Market 14% ili $7 (manje), Limit 24% ili $12 (manje)
     *
     * <p>Default flow (public REST): {@code internalActor=false}. Vidi
     * {@link #createOrder(CreateOrderDto, boolean)} za interne pozivace
     * koji preskacu OTP guard (npr. RecurringOrderService scheduler).
     */
    default OrderDto createOrder(CreateOrderDto dto) {
        return createOrder(dto, false);
    }

    /**
     * Kreiranje ordera sa eksplicitnim {@code internalActor} flag-om.
     *
     * <p><b>ACCEPTED-DEVIATION (user-directed 03.06):</b> OTP/verifikacioni kod je
     * UKLONJEN sa kreiranja ordera (OTP ostaje samo na placanja+transfere).
     * Nijedan flow vise ne verifikuje {@code dto.otpCode}. Flag {@code internalActor}
     * je zadrzan samo radi log-semantike (system vs public-initiated) — NE menja
     * biznis logiku (limit / approval / fund reservation / notify identicni za oba).
     *
     * <p>{@code internalActor=false} (default, public REST flow): poziva ga
     * {@code OrderController} (direktno, bez OTP gate-a).
     *
     * <p>{@code internalActor=true} (scheduler / system-initiated flow): pozivac
     * je trading-service interni servis (npr.
     * {@link rs.raf.trading.recurringorder.service.RecurringOrderService}).
     */
    OrderDto createOrder(CreateOrderDto dto, boolean internalActor);

    /**
     * Supervizor odobrava order.
     * 1. Proveriti da je order u statusu PENDING
     * 2. Proveriti da order nije vremenski ogranicen (settlementDate nije prosao)
     * 3. Postaviti status na APPROVED, approvedBy na ime supervizora
     * 4. Pokrenuti izvrsavanje ordera (asinhrono)
     */
    OrderDto approveOrder(Long orderId);

    /**
     * Supervizor odbija order.
     * 1. Proveriti da je order u statusu PENDING
     * 2. Postaviti status na DECLINED, approvedBy na ime supervizora
     */
    OrderDto declineOrder(Long orderId);

    /**
     * Parcijalno otkazivanje ordera koji nije u potpunosti ispunjen.
     * Specifikacija: "Treba omogućiti otkazivanje celog ili dela Order-a
     * koji još uvek nije ispunjen" (Celina 3, deo Portal Pregled ordera).
     * <p>
     * Ako je {@code quantityToCancel} null, >= remainingPortions ili <= 0,
     * ponasa se kao {@link #declineOrder}. Inace skracuje remainingPortions,
     * oslobadja pro-ratu rezervacije (BUY) ili akcija (SELL) i eventualni
     * deo agentovog usedLimit — status naloga ostaje APPROVED.
     */
    OrderDto cancelOrder(Long orderId, Integer quantityToCancel);

    /**
     * Pregled svih ordera (za supervizora).
     * Filtriranje po statusu: ALL, PENDING, APPROVED, DECLINED, DONE.
     *
     * <p>BE-ORD-03: FUND ordere podrazumevano iskljucuje iz approval view-a —
     * supervizori NE treba da odobravaju fund-management ordere kroz opstu
     * approval listu (oni idu kroz fund admin view, po manager-u fonda).
     * Stari pozivaoci koji ne prosledjuju {@code excludeFund} dobijaju
     * default {@code true}.
     */
    default Page<OrderDto> getAllOrders(String status, int page, int size) {
        return getAllOrders(status, page, size, true);
    }

    /**
     * Prosirena varijanta {@link #getAllOrders(String, int, int)} sa
     * eksplicitnim FUND-exclude flag-om. {@code excludeFund=false} se koristi
     * samo iz fund admin view-a koji namerno hoce FUND ordere.
     */
    Page<OrderDto> getAllOrders(String status, int page, int size, boolean excludeFund);

    /**
     * Pregled ordera jednog korisnika (za korisnika) sa opcionim filterima.
     *
     * @param page       nula-bazirani broj stranice
     * @param size       velicina stranice
     * @param status     opcionalni filter po statusu (PENDING, APPROVED, DECLINED, DONE)
     * @param dateFrom   opcionalni filter — orderi kreirani posle ovog datuma (ukljucivo)
     * @param dateTo     opcionalni filter — orderi kreirani pre ovog datuma (ukljucivo)
     * @param listingType opcionalni filter po tipu hartije (STOCK, FUTURES, FOREX)
     */
    Page<OrderDto> getMyOrders(int page, int size, String status, LocalDate dateFrom, LocalDate dateTo, String listingType);

    /**
     * Detalji jednog ordera.
     */
    OrderDto getOrderById(Long orderId);
}
