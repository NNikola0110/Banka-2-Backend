package rs.raf.trading.stock.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.trading.berza.repository.ExchangeRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingDailyPriceInfo;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingDailyPriceInfoRepository;
import rs.raf.trading.stock.repository.ListingRepository;
import rs.raf.trading.stock.service.implementation.ListingServiceImpl;

import jakarta.persistence.EntityNotFoundException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentMatchers;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ListingServiceImplTest {

    @Mock private ListingRepository listingRepository;
    @Mock private ListingDailyPriceInfoRepository dailyPriceRepository;
    @Mock private ExchangeRepository exchangeRepository;

    @InjectMocks
    private ListingServiceImpl listingService;

    @org.junit.jupiter.api.BeforeEach
    void stubExchangeRepositoryDefaults() {
        // Default-no-op stubs — ako test ne brine za test-mode anotaciju,
        // listing.isTestMode izlazi kao null sto je sasvim ispravno.
        lenient().when(exchangeRepository.findAll()).thenReturn(List.of());
        lenient().when(exchangeRepository.findByAcronym(ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void mockAsEmployee() {
        mockWithRole("ROLE_EMPLOYEE");
    }

    private void mockAsClient() {
        mockWithRole("ROLE_CLIENT");
    }

    private void mockWithRole(String role) {
        Authentication auth = mock(Authentication.class);
        lenient().when(auth.getAuthorities()).thenAnswer(inv ->
                List.of(new SimpleGrantedAuthority(role)));
        SecurityContext ctx = mock(SecurityContext.class);
        lenient().when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
    }

    private Listing listing(String ticker, String name, ListingType type) {
        Listing l = new Listing();
        l.setId(1L);
        l.setTicker(ticker);
        l.setName(name);
        l.setListingType(type);
        l.setPrice(BigDecimal.valueOf(100));
        l.setPriceChange(BigDecimal.valueOf(2));
        return l;
    }

    @Nested
    @DisplayName("getListings - validacija tipa")
    class TypeValidation {

        @Test
        @DisplayName("baca IllegalArgumentException za nepoznat tip")
        void invalidType_throwsIllegalArgument() {
            mockAsEmployee();
            assertThatThrownBy(() -> listingService.getListings("INVALID", null, 0, 20))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("INVALID");
        }

        @Test
        @DisplayName("baca IllegalArgumentException za null tip")
        void nullType_throwsIllegalArgument() {
            mockAsEmployee();
            assertThatThrownBy(() -> listingService.getListings(null, null, 0, 20))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("prihvata mala slova u tipu")
        void lowercaseType_accepted() {
            mockAsEmployee();
            Page<Listing> page = new PageImpl<>(List.of(listing("AAPL", "Apple", ListingType.STOCK)));
            when(listingRepository.findAll(ArgumentMatchers.<Specification<Listing>>any(), any(Pageable.class))).thenReturn(page);

            var result = listingService.getListings("stock", null, 0, 20);

            assertThat(result.getContent()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("getListings - CLIENT vs FOREX")
    class ClientForexRestriction {

        @Test
        @DisplayName("klijent sa FOREX tipom dobija IllegalStateException")
        void client_requestingForex_throwsForbidden() {
            mockAsClient();
            assertThatThrownBy(() -> listingService.getListings("FOREX", null, 0, 20))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FOREX");
        }

        @Test
        @DisplayName("klijent moze da vidi STOCK")
        void client_requestingStock_ok() {
            mockAsClient();
            Page<Listing> page = new PageImpl<>(List.of(listing("AAPL", "Apple", ListingType.STOCK)));
            when(listingRepository.findAll(ArgumentMatchers.<Specification<Listing>>any(), any(Pageable.class))).thenReturn(page);

            var result = listingService.getListings("STOCK", null, 0, 20);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("klijent moze da vidi FUTURES")
        void client_requestingFutures_ok() {
            mockAsClient();
            Page<Listing> page = new PageImpl<>(List.of(listing("CLJ26", "Crude Oil", ListingType.FUTURES)));
            when(listingRepository.findAll(ArgumentMatchers.<Specification<Listing>>any(), any(Pageable.class))).thenReturn(page);

            var result = listingService.getListings("FUTURES", null, 0, 20);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("zaposleni moze da vidi FOREX")
        void employee_requestingForex_ok() {
            mockAsEmployee();
            Page<Listing> page = new PageImpl<>(List.of(listing("EUR/USD", "Euro/Dollar", ListingType.FOREX)));
            when(listingRepository.findAll(ArgumentMatchers.<Specification<Listing>>any(), any(Pageable.class))).thenReturn(page);

            var result = listingService.getListings("FOREX", null, 0, 20);

            assertThat(result.getContent()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("getListingById")
    class GetListingById {

        @Test
        @DisplayName("vraca DTO za postojeci STOCK listing (zaposleni)")
        void existingStock_asEmployee_returnsDto() {
            mockAsEmployee();
            Listing l = listing("AAPL", "Apple Inc.", ListingType.STOCK);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(l));

            var result = listingService.getListingById(1L);

            assertThat(result.getTicker()).isEqualTo("AAPL");
        }

        @Test
        @DisplayName("zaposleni moze da pristupi FOREX listingu")
        void existingForex_asEmployee_returnsDto() {
            mockAsEmployee();
            Listing l = listing("EUR/USD", "Euro/Dollar", ListingType.FOREX);
            when(listingRepository.findById(2L)).thenReturn(Optional.of(l));

            var result = listingService.getListingById(2L);

            assertThat(result.getTicker()).isEqualTo("EUR/USD");
        }

        @Test
        @DisplayName("klijent moze da pristupi STOCK listingu")
        void existingStock_asClient_returnsDto() {
            mockAsClient();
            Listing l = listing("MSFT", "Microsoft", ListingType.STOCK);
            when(listingRepository.findById(3L)).thenReturn(Optional.of(l));

            var result = listingService.getListingById(3L);

            assertThat(result.getTicker()).isEqualTo("MSFT");
        }

        @Test
        @DisplayName("klijent moze da pristupi FUTURES listingu")
        void existingFutures_asClient_returnsDto() {
            mockAsClient();
            Listing l = listing("CLJ26", "Crude Oil", ListingType.FUTURES);
            when(listingRepository.findById(4L)).thenReturn(Optional.of(l));

            var result = listingService.getListingById(4L);

            assertThat(result.getTicker()).isEqualTo("CLJ26");
        }

        @Test
        @DisplayName("baca EntityNotFoundException za nepostojeci ID")
        void nonExistent_throwsEntityNotFoundException() {
            mockAsEmployee();
            when(listingRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> listingService.getListingById(99L))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("klijent sa FOREX listingom dobija IllegalStateException")
        void forexListing_asClient_throwsIllegalStateException() {
            mockAsClient();
            Listing l = listing("EUR/USD", "Euro/Dollar", ListingType.FOREX);
            when(listingRepository.findById(5L)).thenReturn(Optional.of(l));

            assertThatThrownBy(() -> listingService.getListingById(5L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FOREX");
        }

        @Test
        @DisplayName("vraca DTO sa izvedenim poljima")
        void returnsDto_withDerivedFields() {
            mockAsEmployee();
            Listing l = listing("AAPL", "Apple Inc.", ListingType.STOCK);
            l.setOutstandingShares(1_000_000L);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(l));

            var result = listingService.getListingById(1L);

            assertThat(result.getMarketCap()).isNotNull();
            assertThat(result.getChangePercent()).isNotNull();
            assertThat(result.getMaintenanceMargin()).isNotNull();
            assertThat(result.getInitialMarginCost()).isNotNull();
        }
    }

    private ListingDailyPriceInfo dailyPrice(Listing listing, LocalDate date) {
        ListingDailyPriceInfo info = new ListingDailyPriceInfo();
        info.setListing(listing);
        info.setDate(date);
        info.setPrice(BigDecimal.valueOf(150));
        info.setHigh(BigDecimal.valueOf(155));
        info.setLow(BigDecimal.valueOf(145));
        info.setChange(BigDecimal.valueOf(3));
        info.setVolume(10_000L);
        return info;
    }

    @Nested
    @DisplayName("getListingHistory")
    class GetListingHistory {

        @Test
        @DisplayName("baca EntityNotFoundException za nepostojeci listing")
        void nonExistentListing_throwsEntityNotFoundException() {
            mockAsEmployee();
            when(listingRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> listingService.getListingHistory(99L, "MONTH"))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("klijent koji pristupa FOREX istoriji dobija IllegalStateException")
        void clientAccessingForexHistory_throwsForbidden() {
            mockAsClient();
            Listing l = listing("EUR/USD", "Euro/Dollar", ListingType.FOREX);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(l));

            assertThatThrownBy(() -> listingService.getListingHistory(1L, "MONTH"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FOREX");
        }

        @Test
        @DisplayName("nevalidan period baca IllegalArgumentException")
        void invalidPeriod_throwsIllegalArgumentException() {
            mockAsEmployee();
            Listing l = listing("AAPL", "Apple", ListingType.STOCK);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(l));

            assertThatThrownBy(() -> listingService.getListingHistory(1L, "INVALID"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("zaposleni moze da pristupi FOREX istoriji")
        void employeeAccessingForexHistory_ok() {
            mockAsEmployee();
            Listing l = listing("EUR/USD", "Euro/Dollar", ListingType.FOREX);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(l));
            when(dailyPriceRepository.findByListingIdAndDateAfterOrderByDateDesc(eq(1L), any()))
                    .thenReturn(List.of(dailyPrice(l, LocalDate.now())));

            var result = listingService.getListingHistory(1L, "MONTH");

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("klijent moze da pristupi STOCK istoriji")
        void clientAccessingStockHistory_ok() {
            mockAsClient();
            Listing l = listing("AAPL", "Apple", ListingType.STOCK);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(l));
            when(dailyPriceRepository.findByListingIdAndDateAfterOrderByDateDesc(eq(1L), any()))
                    .thenReturn(List.of(dailyPrice(l, LocalDate.now())));

            var result = listingService.getListingHistory(1L, "MONTH");

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("klijent moze da pristupi FUTURES istoriji")
        void clientAccessingFuturesHistory_ok() {
            mockAsClient();
            Listing l = listing("CLJ26", "Crude Oil", ListingType.FUTURES);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(l));
            when(dailyPriceRepository.findByListingIdAndDateAfterOrderByDateDesc(eq(1L), any()))
                    .thenReturn(List.of());

            var result = listingService.getListingHistory(1L, "WEEK");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("DAY period poziva findByListingIdAndDate sa danasnjim datumom")
        void dayPeriod_callsFindByDate() {
            mockAsEmployee();
            Listing l = listing("AAPL", "Apple", ListingType.STOCK);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(l));
            when(dailyPriceRepository.findByListingIdAndDate(eq(1L), eq(LocalDate.now())))
                    .thenReturn(List.of(dailyPrice(l, LocalDate.now())));

            var result = listingService.getListingHistory(1L, "DAY");

            verify(dailyPriceRepository).findByListingIdAndDate(1L, LocalDate.now());
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("WEEK period poziva findByListingIdAndDateAfterOrderByDateDesc sa danas - 7 dana")
        void weekPeriod_callsFindByDateAfter() {
            mockAsEmployee();
            Listing l = listing("AAPL", "Apple", ListingType.STOCK);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(l));
            when(dailyPriceRepository.findByListingIdAndDateAfterOrderByDateDesc(eq(1L), eq(LocalDate.now().minusDays(7))))
                    .thenReturn(List.of());

            listingService.getListingHistory(1L, "WEEK");

            verify(dailyPriceRepository).findByListingIdAndDateAfterOrderByDateDesc(1L, LocalDate.now().minusDays(7));
        }

        @Test
        @DisplayName("MONTH period poziva findByListingIdAndDateAfterOrderByDateDesc sa danas - 30 dana")
        void monthPeriod_callsFindByDateAfter() {
            mockAsEmployee();
            Listing l = listing("AAPL", "Apple", ListingType.STOCK);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(l));
            when(dailyPriceRepository.findByListingIdAndDateAfterOrderByDateDesc(eq(1L), eq(LocalDate.now().minusDays(30))))
                    .thenReturn(List.of());

            listingService.getListingHistory(1L, "MONTH");

            verify(dailyPriceRepository).findByListingIdAndDateAfterOrderByDateDesc(1L, LocalDate.now().minusDays(30));
        }

        @Test
        @DisplayName("YEAR period poziva findByListingIdAndDateAfterOrderByDateDesc sa danas - 1 godina")
        void yearPeriod_callsFindByDateAfter() {
            mockAsEmployee();
            Listing l = listing("AAPL", "Apple", ListingType.STOCK);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(l));
            when(dailyPriceRepository.findByListingIdAndDateAfterOrderByDateDesc(eq(1L), eq(LocalDate.now().minusYears(1))))
                    .thenReturn(List.of());

            listingService.getListingHistory(1L, "YEAR");

            verify(dailyPriceRepository).findByListingIdAndDateAfterOrderByDateDesc(1L, LocalDate.now().minusYears(1));
        }

        @Test
        @DisplayName("FIVE_YEARS period poziva findByListingIdAndDateAfterOrderByDateDesc sa danas - 5 godina")
        void fiveYearsPeriod_callsFindByDateAfter() {
            mockAsEmployee();
            Listing l = listing("AAPL", "Apple", ListingType.STOCK);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(l));
            when(dailyPriceRepository.findByListingIdAndDateAfterOrderByDateDesc(eq(1L), eq(LocalDate.now().minusYears(5))))
                    .thenReturn(List.of());

            listingService.getListingHistory(1L, "FIVE_YEARS");

            verify(dailyPriceRepository).findByListingIdAndDateAfterOrderByDateDesc(1L, LocalDate.now().minusYears(5));
        }

        @Test
        @DisplayName("ALL period poziva findByListingIdOrderByDateDesc")
        void allPeriod_callsFindAll() {
            mockAsEmployee();
            Listing l = listing("AAPL", "Apple", ListingType.STOCK);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(l));
            when(dailyPriceRepository.findByListingIdOrderByDateDesc(1L)).thenReturn(List.of());

            listingService.getListingHistory(1L, "ALL");

            verify(dailyPriceRepository).findByListingIdOrderByDateDesc(1L);
        }

        @Test
        @DisplayName("vraca mapirane ListingDailyPriceDto sa ispravnim poljima")
        void returnsMappedDtos() {
            mockAsEmployee();
            Listing l = listing("AAPL", "Apple", ListingType.STOCK);
            LocalDate today = LocalDate.now();
            ListingDailyPriceInfo info = dailyPrice(l, today);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(l));
            when(dailyPriceRepository.findByListingIdAndDate(1L, today))
                    .thenReturn(List.of(info));

            var result = listingService.getListingHistory(1L, "DAY");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getDate()).isEqualTo(today);
            assertThat(result.get(0).getPrice()).isEqualByComparingTo(BigDecimal.valueOf(150));
        }

        @Test
        @DisplayName("period je case-insensitive")
        void periodIsCaseInsensitive() {
            mockAsEmployee();
            Listing l = listing("AAPL", "Apple", ListingType.STOCK);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(l));
            when(dailyPriceRepository.findByListingIdAndDateAfterOrderByDateDesc(eq(1L), any()))
                    .thenReturn(List.of());

            var result = listingService.getListingHistory(1L, "month");

            assertThat(result).isNotNull();
            verify(dailyPriceRepository).findByListingIdAndDateAfterOrderByDateDesc(eq(1L), any());
        }
    }

    @Nested
    @DisplayName("getListings - paginacija i pretraga")
    class PaginationAndSearch {

        @Test
        @DisplayName("vraca paginiran rezultat")
        void returnsPaginatedResults() {
            mockAsEmployee();
            Page<Listing> page = new PageImpl<>(
                    List.of(listing("AAPL", "Apple", ListingType.STOCK)),
                    org.springframework.data.domain.PageRequest.of(0, 20),
                    1
            );
            when(listingRepository.findAll(ArgumentMatchers.<Specification<Listing>>any(), any(Pageable.class))).thenReturn(page);

            var result = listingService.getListings("STOCK", null, 0, 20);

            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).getTicker()).isEqualTo("AAPL");
        }

        @Test
        @DisplayName("prosledjuje search u Specification")
        void searchIsPassedToRepository() {
            mockAsEmployee();
            when(listingRepository.findAll(ArgumentMatchers.<Specification<Listing>>any(), any(Pageable.class)))
                    .thenReturn(Page.empty());

            listingService.getListings("STOCK", "AAPL", 0, 20);

            verify(listingRepository, times(1))
                    .findAll(ArgumentMatchers.<Specification<Listing>>any(), any(Pageable.class));
        }

        @Test
        @DisplayName("mapira Listing u ListingDto sa izvedenim podacima")
        void mapsToDto_withDerivedFields() {
            mockAsEmployee();
            Listing l = listing("MSFT", "Microsoft", ListingType.STOCK);
            l.setOutstandingShares(1_000_000L);
            Page<Listing> page = new PageImpl<>(List.of(l));
            when(listingRepository.findAll(ArgumentMatchers.<Specification<Listing>>any(), any(Pageable.class))).thenReturn(page);

            var result = listingService.getListings("STOCK", null, 0, 20);

            var dto = result.getContent().get(0);
            assertThat(dto.getTicker()).isEqualTo("MSFT");
            assertThat(dto.getMarketCap()).isNotNull();
            assertThat(dto.getChangePercent()).isNotNull();
            assertThat(dto.getMaintenanceMargin()).isNotNull();
            assertThat(dto.getInitialMarginCost()).isNotNull();
        }
    }
}
