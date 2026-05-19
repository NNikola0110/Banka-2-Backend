package rs.raf.trading.investmentfund.mapper;

import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.investmentfund.dto.InvestmentFundDtos.*;
import rs.raf.trading.investmentfund.model.InvestmentFund;

import java.math.BigDecimal;
import java.util.List;

/**
 * NAPOMENA (copy-first ekstrakcija, faza 2c): monolitni mapper je citao
 * podatke o racunu fonda direktno preko {@code account.model.Account}
 * (balans, id, broj racuna). U trading-service-u racuni zive u banka-core
 * domenu, pa servisni sloj ({@code InvestmentFundService}) pre poziva mapera
 * razresava racun preko {@code BankaCoreClient.getAccount} i prosledjuje
 * pre-razreseni {@link InternalAccountDto} ovom maperu (isti obrazac kao
 * {@code ActuaryMapper} u C1).
 */
public final class InvestmentFundMapper {

    private InvestmentFundMapper() {}

    public static InvestmentFundDetailDto toDetailDto(InvestmentFund fund,
                                                      InternalAccountDto account,
                                                      BigDecimal fundValue,
                                                      BigDecimal profit,
                                                      List<FundHoldingDto> holdings,
                                                      List<FundPerformancePointDto> performance,
                                                      String managerName) {
        return new InvestmentFundDetailDto(
                fund.getId(),
                fund.getName(),
                fund.getDescription(),
                managerName,
                fund.getManagerEmployeeId(),
                fundValue,
                account.balance(),
                profit,
                fund.getMinimumContribution(),
                account.id(),
                account.accountNumber(),
                holdings,
                performance,
                fund.getInceptionDate());
    }

    public static InvestmentFundSummaryDto toSummaryDto(InvestmentFund fund,
                                                        BigDecimal fundValue,
                                                        BigDecimal profit,
                                                        String managerName) {
        return new InvestmentFundSummaryDto(
                fund.getId(),
                fund.getName(),
                fund.getDescription(),
                fund.getMinimumContribution(),
                fundValue,
                profit,
                managerName,
                fund.getInceptionDate());
    }
}
