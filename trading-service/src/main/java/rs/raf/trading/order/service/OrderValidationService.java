package rs.raf.trading.order.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderType;

import java.math.BigDecimal;

@Service
public class OrderValidationService {

    /**
     * Sc27 (Celina 3, TestoviCelina3 §27): globalna minimalna dozvoljena kolicina za
     * trgovanje. Order ispod ovog praga se odbija sa porukom o minimalnoj kolicini —
     * pre svake druge biznis logike (cena/rezervacija/status), da klijent dobije
     * deterministican 400 umesto da order tiho prodje na izvrsavanje.
     *
     * <p>Konfigurabilno preko {@code trading.order.min-quantity} (default 1). Default 1
     * cuva postojece ponasanje ({@code quantity > 0} = Sc24); operater moze podici
     * prag bez izmene koda. Unit testovi koji instanciraju {@code new
     * OrderValidationService()} koriste field default (1) ili ga eksplicitno podese
     * preko {@link #setMinTradeQuantity(int)}.
     */
    @Value("${trading.order.min-quantity:1}")
    private int minTradeQuantity = 1;

    public void validate(CreateOrderDto dto) {
        OrderType orderType = parseOrderType(dto.getOrderType());
        parseDirection(dto.getDirection());

        if (dto.getQuantity() == null || dto.getQuantity() <= 0 ||
                dto.getContractSize() == null || dto.getContractSize() <= 0) {
            throw new IllegalArgumentException("Quantity and contractSize must be > 0");
        }

        // Sc27: kolicina ispod minimalne dozvoljene → odbij + poruka o min kolicini.
        // Prag >= 1 uvek (sanity); za default 1 ovaj uslov nikad ne okida (vec ga
        // pokriva quantity > 0 iznad), pa je ponasanje bez konfiguracije nepromenjeno.
        int effectiveMin = Math.max(1, minTradeQuantity);
        if (dto.getQuantity() < effectiveMin) {
            throw new IllegalArgumentException(
                    "Količina je ispod minimalne dozvoljene za trgovanje. Minimalna količina: "
                            + effectiveMin + ", uneto: " + dto.getQuantity());
        }

        if (orderType == OrderType.LIMIT || orderType == OrderType.STOP_LIMIT) {
            if (dto.getLimitValue() == null || dto.getLimitValue().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Limit value is required for LIMIT and STOP_LIMIT orders");
            }
        }

        if (orderType == OrderType.STOP || orderType == OrderType.STOP_LIMIT) {
            if (dto.getStopValue() == null || dto.getStopValue().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Stop value is required for STOP and STOP_LIMIT orders");
            }
        }

        // XOR enforcement: order mora imati ili accountId (klijent/zaposleni
        // direktan order) ili fundId (supervizor kupuje u ime fonda — P3 /
        // Celina 4 (Nova) §3883-3964), ali NE oba (kontradikcija).
        // Bez ovog XOR-a se FUND BUY supervizora gusi pre nego sto OrderServiceImpl
        // stigne da resolve-uje fund.accountId.
        if (dto.getAccountId() == null && dto.getFundId() == null) {
            throw new IllegalArgumentException("Either accountId or fundId is required");
        }
        if (dto.getAccountId() != null && dto.getFundId() != null) {
            throw new IllegalArgumentException("accountId and fundId are mutually exclusive");
        }
    }

    public OrderType parseOrderType(String orderType) {
        try {
            return OrderType.valueOf(orderType);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid order type or direction");
        }
    }

    public OrderDirection parseDirection(String direction) {
        try {
            return OrderDirection.valueOf(direction);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid order type or direction");
        }
    }

    /**
     * Sc27: konfiguracija minimalne kolicine za trgovanje (vidljiva i za testove).
     * Vrednost {@code <= 0} se klampuje na 1 pri validaciji (vidi {@link #validate}).
     */
    void setMinTradeQuantity(int minTradeQuantity) {
        this.minTradeQuantity = minTradeQuantity;
    }

    int getMinTradeQuantity() {
        return minTradeQuantity;
    }
}
