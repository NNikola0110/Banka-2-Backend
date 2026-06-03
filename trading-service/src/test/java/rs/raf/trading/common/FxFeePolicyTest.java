package rs.raf.trading.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-profit-fx-fee-1 (R5 1877) — karakterizacioni test za konsolidaciju FX fee.
 *
 * <p>Pre konsolidacije je ista 1% konstanta bila nezavisno duplirana na 4 mesta.
 * Ovaj test je "single source of truth + nepromenjena vrednost" garancija:
 * (a) politika i dalje vraca tacno 0.01 (1%) — efektivna naknada NEPROMENJENA;
 * (b) sva 4 servisna polja sada SOURCE iz {@link FxFeePolicy#FX_FEE_RATE} (isti
 * instance / ista numericka vrednost), pa ne mogu da divergiraju.</p>
 */
@DisplayName("FxFeePolicy — jedinstven izvor FX fee (R5 1877)")
class FxFeePolicyTest {

    @Test
    @DisplayName("Politika vraca tacno 0.01 (1%) — efektivna naknada NEPROMENJENA posle konsolidacije")
    void fxFeeRate_isOnePercent_unchanged() {
        assertThat(FxFeePolicy.FX_FEE_RATE).isEqualByComparingTo("0.01");
        // byte-egzaktan scale (string "0.01") da multiplikacija ostane bit-identicna staroj.
        assertThat(FxFeePolicy.FX_FEE_RATE).isEqualTo(new BigDecimal("0.01"));
    }

    @Test
    @DisplayName("Sva 4 callera sada source iz jedinstvenog FxFeePolicy.FX_FEE_RATE")
    void allFourCallers_sourceFromSinglePolicy() throws Exception {
        BigDecimal policy = FxFeePolicy.FX_FEE_RATE;

        assertSame(policy, "rs.raf.trading.order.service.CurrencyConversionService", "FX_MARGIN");
        assertSame(policy, "rs.raf.trading.order.service.SingleOrderExecutor", "SELL_FX_MARGIN");
        assertSame(policy, "rs.raf.trading.investmentfund.service.InvestmentFundService", "FX_FEE_RATE");
        assertSame(policy, "rs.raf.trading.investmentfund.service.FundLiquidationService", "FX_FEE_RATE");
    }

    private static void assertSame(BigDecimal policy, String className, String fieldName) throws Exception {
        Class<?> clazz = Class.forName(className);
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        BigDecimal value = (BigDecimal) field.get(null);
        // ista referenca (assign-by-reference iz politike) -> ista numericka vrednost garantovana.
        assertThat(value)
                .as("%s.%s mora da source iz FxFeePolicy.FX_FEE_RATE", className, fieldName)
                .isSameAs(policy);
    }
}
