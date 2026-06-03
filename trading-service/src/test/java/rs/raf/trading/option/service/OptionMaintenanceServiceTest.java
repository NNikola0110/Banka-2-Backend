package rs.raf.trading.option.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.trading.option.model.Option;
import rs.raf.trading.option.model.OptionType;
import rs.raf.trading.option.repository.OptionRepository;
import rs.raf.trading.stock.model.Listing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * P2-money-tx-1 (R3 1587): testovi logike odrzavanja opcija, premesteni iz
 * {@code OptionSchedulerTest} u {@link OptionMaintenanceService} (nosilac
 * {@code @Transactional} jedinica). Verifikuje cleanup/recalc/generate logiku
 * koja je ranije zivela u scheduleru kao {@code protected} self-invocation
 * (AOP NO-OP) metode.
 */
@ExtendWith(MockitoExtension.class)
class OptionMaintenanceServiceTest {

    @Mock
    private OptionRepository optionRepository;

    @Mock
    private OptionGeneratorService optionGeneratorService;

    @Mock
    private BlackScholesService blackScholesService;

    @InjectMocks
    private OptionMaintenanceService maintenanceService;

    private Option buildOption(Long id, OptionType type, BigDecimal strikePrice,
                               BigDecimal stockPrice, LocalDate settlementDate) {
        Listing listing = new Listing();
        listing.setId(id);
        listing.setPrice(stockPrice);

        Option option = new Option();
        option.setId(id);
        option.setOptionType(type);
        option.setStrikePrice(strikePrice);
        option.setImpliedVolatility(0.25);
        option.setStockListing(listing);
        option.setSettlementDate(settlementDate);
        option.setPrice(BigDecimal.TEN);
        option.setAsk(BigDecimal.valueOf(10.50));
        option.setBid(BigDecimal.valueOf(9.50));
        option.setTicker("TEST" + id);
        return option;
    }

    @Nested
    @DisplayName("cleanupExpiredOptions")
    class CleanupExpiredOptions {

        @Test
        @DisplayName("deletes expired options when they exist")
        void deletesExpiredOptions() {
            Option expired = buildOption(1L, OptionType.CALL, BigDecimal.valueOf(100),
                    BigDecimal.valueOf(110), LocalDate.now().minusDays(1));
            when(optionRepository.findBySettlementDateBefore(any())).thenReturn(List.of(expired));

            maintenanceService.cleanupExpiredOptions();

            verify(optionRepository).deleteBySettlementDateBefore(any());
        }

        @Test
        @DisplayName("does not call delete when no expired options")
        void doesNotDeleteWhenNoExpired() {
            when(optionRepository.findBySettlementDateBefore(any())).thenReturn(Collections.emptyList());

            maintenanceService.cleanupExpiredOptions();

            verify(optionRepository, never()).deleteBySettlementDateBefore(any());
        }
    }

    @Nested
    @DisplayName("generateNewOptions")
    class GenerateNewOptions {

        @Test
        @DisplayName("delegates to generator service")
        void delegates() {
            maintenanceService.generateNewOptions();
            verify(optionGeneratorService).generateAllOptions();
        }
    }

    @Nested
    @DisplayName("recalculatePrices")
    class RecalculatePrices {

        @Test
        @DisplayName("recalculates CALL option price using Black-Scholes")
        void recalculatesCallOption() {
            Option callOption = buildOption(1L, OptionType.CALL, BigDecimal.valueOf(100),
                    BigDecimal.valueOf(110), LocalDate.now().plusDays(30));

            when(optionRepository.findAllWithStockListing()).thenReturn(List.of(callOption));
            when(blackScholesService.calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(BigDecimal.valueOf(15.5000));

            maintenanceService.recalculatePrices();

            verify(blackScholesService).calculateCallPrice(
                    eq(110.0), eq(100.0), anyDouble(), eq(0.25));
            verify(optionRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("recalculates PUT option price using Black-Scholes")
        void recalculatesPutOption() {
            Option putOption = buildOption(1L, OptionType.PUT, BigDecimal.valueOf(150),
                    BigDecimal.valueOf(140), LocalDate.now().plusDays(60));

            when(optionRepository.findAllWithStockListing()).thenReturn(List.of(putOption));
            when(blackScholesService.calculatePutPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(BigDecimal.valueOf(12.3400));

            maintenanceService.recalculatePrices();

            verify(blackScholesService).calculatePutPrice(
                    eq(140.0), eq(150.0), anyDouble(), eq(0.25));
        }

        @Test
        @DisplayName("skips option with null stock price")
        void skipsOptionWithNullStockPrice() {
            Option option = buildOption(1L, OptionType.CALL, BigDecimal.valueOf(100),
                    null, LocalDate.now().plusDays(30));
            option.getStockListing().setPrice(null);

            when(optionRepository.findAllWithStockListing()).thenReturn(List.of(option));

            maintenanceService.recalculatePrices();

            verify(blackScholesService, never()).calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble());
            verify(blackScholesService, never()).calculatePutPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("skips option with settlement date today or in the past (daysToExpiry <= 0)")
        void skipsExpiredOption() {
            Option option = buildOption(1L, OptionType.CALL, BigDecimal.valueOf(100),
                    BigDecimal.valueOf(110), LocalDate.now());

            when(optionRepository.findAllWithStockListing()).thenReturn(List.of(option));

            maintenanceService.recalculatePrices();

            verify(blackScholesService, never()).calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("updates ask to price * 1.05 and bid to price * 0.95")
        void updatesAskAndBid() {
            Option option = buildOption(1L, OptionType.CALL, BigDecimal.valueOf(100),
                    BigDecimal.valueOf(110), LocalDate.now().plusDays(30));
            BigDecimal newPrice = BigDecimal.valueOf(20.0000);

            when(optionRepository.findAllWithStockListing()).thenReturn(List.of(option));
            when(blackScholesService.calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(newPrice);

            maintenanceService.recalculatePrices();

            assertThat(option.getPrice()).isEqualByComparingTo(newPrice);
            assertThat(option.getAsk()).isEqualByComparingTo(new BigDecimal("21.0000"));
            assertThat(option.getBid()).isEqualByComparingTo(new BigDecimal("19.0000"));
        }

        @Test
        @DisplayName("handles empty option list without error")
        void handlesEmptyList() {
            when(optionRepository.findAllWithStockListing()).thenReturn(Collections.emptyList());

            assertThatNoException().isThrownBy(() -> maintenanceService.recalculatePrices());

            verify(optionRepository).saveAll(Collections.emptyList());
        }

        @Test
        @DisplayName("R1 458: recalculatePrices osvezava maintenanceMargin po novoj ceni akcije (ne ostaje stale)")
        void recalculatesMaintenanceMargin() {
            Option option = buildOption(1L, OptionType.CALL, BigDecimal.valueOf(100),
                    BigDecimal.valueOf(120), LocalDate.now().plusDays(30));
            option.setContractSize(100);
            // Stara (stale) margina pre recalc-a — npr. od dana generisanja po staroj ceni.
            option.setMaintenanceMargin(new BigDecimal("5000.0000"));

            when(optionRepository.findAllWithStockListing()).thenReturn(List.of(option));
            when(blackScholesService.calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(BigDecimal.valueOf(22.0000));
            // Generator overload: 100 × 0.5 × 120 = 6000.
            when(optionGeneratorService.computeMaintenanceMargin(eq(100), eq(BigDecimal.valueOf(120))))
                    .thenReturn(new BigDecimal("6000.0000"));

            maintenanceService.recalculatePrices();

            // Margin je ponovo izracunat po TRENUTNOJ ceni akcije (nije ostao 5000).
            verify(optionGeneratorService).computeMaintenanceMargin(eq(100), eq(BigDecimal.valueOf(120)));
            assertThat(option.getMaintenanceMargin()).isEqualByComparingTo(new BigDecimal("6000.0000"));
        }

        @Test
        @DisplayName("R1 458: saveAll prima SAMO izmenjene opcije (preskocene null-price se ne persistuju)")
        void saveAllReceivesOnlyChangedOptions() {
            Option priced = buildOption(1L, OptionType.CALL, BigDecimal.valueOf(100),
                    BigDecimal.valueOf(110), LocalDate.now().plusDays(30));
            priced.setContractSize(100);
            Option nullPrice = buildOption(2L, OptionType.PUT, BigDecimal.valueOf(90),
                    BigDecimal.valueOf(80), LocalDate.now().plusDays(30));
            nullPrice.getStockListing().setPrice(null);

            when(optionRepository.findAllWithStockListing()).thenReturn(List.of(priced, nullPrice));
            when(blackScholesService.calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(BigDecimal.valueOf(15.0000));
            when(optionGeneratorService.computeMaintenanceMargin(anyInt(), any()))
                    .thenReturn(new BigDecimal("5500.0000"));

            maintenanceService.recalculatePrices();

            // Samo 'priced' (1 opcija) ide u saveAll — nullPrice je preskocen.
            org.mockito.ArgumentCaptor<List<Option>> captor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(optionRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).containsExactly(priced);
        }
    }
}
