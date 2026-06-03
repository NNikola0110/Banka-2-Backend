package rs.raf.trading.profitbank.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

public final class ProfitBankDtos {

    private ProfitBankDtos() {}

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ActuaryProfitDto {
        private Long employeeId;
        private String name;
        private String position; // "SUPERVISOR" ili "AGENT"
        private BigDecimal totalProfitRsd;
        private Integer ordersDone;
    }

    // R1 506: BankFundPositionDto uklonjen — 0 BE callera. ProfitBankController
    // .fundPositions() vraca InvestmentFundDtos.ClientFundPositionDto. (Mobile-ova
    // istoimena Kotlin klasa je zaseban DTO koji cita ClientFundPositionDto JSON.)
}
