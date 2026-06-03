package rs.raf.trading.profitbank.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.profitbank.dto.ProfitBankDtos.ActuaryProfitDto;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Jedinicni test {@link ActuaryProfitService} — pokriva profit agregaciju
 * (pure-compute jezgro) protiv trading-service {@code Order}/{@code Listing}.
 *
 * <p>NAPOMENA (copy-first ekstrakcija, faza 2d-E): {@code profitbank} paket u
 * monolitu nije imao testove. Ovo je novi test pisan po obrascu 2d
 * {@code option}/{@code margin} servisnih testova — banka-core identitet
 * ({@code getUserById}/{@code getUserPermissions}) je mockovan.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActuaryProfitServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private CurrencyConversionService currencyConversionService;

    @InjectMocks
    private ActuaryProfitService service;

    // ── helpers ──────────────────────────────────────────────────────────────

    private Listing listing(Long id, String exchange) {
        Listing l = new Listing();
        l.setId(id);
        l.setTicker("T" + id);
        l.setName("Listing " + id);
        l.setExchangeAcronym(exchange);
        l.setListingType(ListingType.STOCK);
        l.setPrice(new BigDecimal("100.00"));
        return l;
    }

    private Order order(Long actuarId, String userRole, Listing listing,
                        OrderDirection direction, int qty, String pricePerUnit) {
        Order o = new Order();
        o.setUserId(actuarId);
        o.setUserRole(userRole);
        o.setListing(listing);
        o.setDirection(direction);
        o.setQuantity(qty);
        o.setContractSize(1);
        o.setPricePerUnit(pricePerUnit == null ? null : new BigDecimal(pricePerUnit));
        o.setDone(true);
        return o;
    }

    private void mockEmployee(Long id, String firstName, String lastName,
                              String email, List<String> permissions) {
        when(bankaCoreClient.getUserById(UserRole.EMPLOYEE, id)).thenReturn(
                new InternalUserDto(id, "EMPLOYEE", email, firstName, lastName, true, "Agent"));
        lenient().when(bankaCoreClient.getUserPermissions(email)).thenReturn(permissions);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void emptyOrders_returnsEmptyList() {
        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of());

        assertThat(service.listAllActuariesProfit()).isEmpty();
        verify(bankaCoreClient, never()).getUserById(anyString(), any());
    }

    @Test
    void singleActuary_sellMinusBuy_sameCurrency_noFx() {
        Listing belex = listing(1L, "BELEX"); // RSD listing
        // BUY 10 @ 100 = 1000 cost; SELL 10 @ 150 = 1500 value; profit = 500 RSD
        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(
                order(7L, UserRole.EMPLOYEE, belex, OrderDirection.BUY, 10, "100.00"),
                order(7L, UserRole.EMPLOYEE, belex, OrderDirection.SELL, 10, "150.00")));
        mockEmployee(7L, "Ana", "Anic", "ana@test.com", List.of("AGENT"));

        List<ActuaryProfitDto> result = service.listAllActuariesProfit();

        assertThat(result).hasSize(1);
        ActuaryProfitDto dto = result.get(0);
        assertThat(dto.getEmployeeId()).isEqualTo(7L);
        assertThat(dto.getName()).isEqualTo("Ana Anic");
        assertThat(dto.getPosition()).isEqualTo("AGENT");
        assertThat(dto.getTotalProfitRsd()).isEqualByComparingTo("500.00");
        assertThat(dto.getOrdersDone()).isEqualTo(2);
        // RSD listing -> nikad ne zove FX konverziju
        verify(currencyConversionService, never()).convert(any(), anyString(), anyString());
    }

    @Test
    void foreignCurrencyListing_convertsProfitToRsd() {
        Listing nasdaq = listing(2L, "NASDAQ"); // USD listing
        // SELL 5 @ 200 = 1000 USD; BUY 5 @ 120 = 600 USD; profit = 400 USD
        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(
                order(8L, UserRole.EMPLOYEE, nasdaq, OrderDirection.BUY, 5, "120.00"),
                order(8L, UserRole.EMPLOYEE, nasdaq, OrderDirection.SELL, 5, "200.00")));
        mockEmployee(8L, "Marko", "Markic", "marko@test.com", List.of("SUPERVISOR"));
        // 400 USD -> 47200 RSD (kurs 118)
        when(currencyConversionService.convert(new BigDecimal("400.00"), "USD", "RSD"))
                .thenReturn(new BigDecimal("47200.00"));

        List<ActuaryProfitDto> result = service.listAllActuariesProfit();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTotalProfitRsd()).isEqualByComparingTo("47200.00");
        assertThat(result.get(0).getPosition()).isEqualTo("SUPERVISOR");
    }

    @Test
    void eurListing_convertsRealizedProfitToRsd_singleConvertCall() {
        // OT-1167 (TEST-tr-funds-dividends-profitbank-1): non-USD strana valuta
        // (EUR-listing) se konvertuje u RSD jednim convert(EUR->RSD) pozivom.
        // CurrencyConversionService apstrakuje eventualnu triangulaciju (EUR->RSD
        // direktno ili preko medjuvalute) — servis joj prosledjuje (amount, EUR, RSD)
        // i sabira vraceni RSD iznos. Pinujemo da se valuta listinga (EUR) prosledjuje
        // tacno, da se ne mesa sa drugom valutom, i da rezultat ulazi u total.
        Listing xetra = listing(30L, "XETRA"); // EUR listing (non-USD)
        // BUY 5 @ 80 = 400 EUR cost; SELL 5 @ 130 = 650 EUR; realizovan profit = 250 EUR
        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(
                order(40L, UserRole.EMPLOYEE, xetra, OrderDirection.BUY, 5, "80.00"),
                order(40L, UserRole.EMPLOYEE, xetra, OrderDirection.SELL, 5, "130.00")));
        mockEmployee(40L, "Euro", "Trejder", "euro@test.com", List.of("SUPERVISOR"));
        // 250 EUR -> 29250 RSD (kurs 117)
        when(currencyConversionService.convert(new BigDecimal("250.00"), "EUR", "RSD"))
                .thenReturn(new BigDecimal("29250.00"));

        List<ActuaryProfitDto> result = service.listAllActuariesProfit();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTotalProfitRsd()).isEqualByComparingTo("29250.00");
        // tacno jedan FX poziv, sa EUR->RSD (ne USD)
        verify(currencyConversionService).convert(new BigDecimal("250.00"), "EUR", "RSD");
        verify(currencyConversionService, never()).convert(any(), eq("USD"), anyString());
    }

    @Test
    void clientAndFundOrders_areIgnored() {
        Listing belex = listing(3L, "BELEX");
        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(
                order(99L, UserRole.CLIENT, belex, OrderDirection.SELL, 10, "150.00"),
                order(50L, "FUND", belex, OrderDirection.SELL, 10, "150.00")));

        assertThat(service.listAllActuariesProfit()).isEmpty();
        verify(bankaCoreClient, never()).getUserById(anyString(), any());
    }

    @Test
    void orderWithNullListing_isSkipped() {
        Listing belex = listing(4L, "BELEX");
        Order nullListingOrder = order(11L, UserRole.EMPLOYEE, belex, OrderDirection.SELL, 1, "10.00");
        nullListingOrder.setListing(null);
        Order validOrder = order(11L, UserRole.EMPLOYEE, belex, OrderDirection.SELL, 2, "100.00");
        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(nullListingOrder, validOrder));
        mockEmployee(11L, "Ivo", "Ivic", "ivo@test.com", List.of("AGENT"));

        List<ActuaryProfitDto> result = service.listAllActuariesProfit();

        // samo validOrder uracunat: SELL 2 @ 100 = 200 RSD profit (nema BUY)
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTotalProfitRsd()).isEqualByComparingTo("200.00");
        assertThat(result.get(0).getOrdersDone()).isEqualTo(1);
    }

    @Test
    void multipleActuaries_sortedByProfitDescending() {
        Listing belex = listing(5L, "BELEX");
        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(
                // aktuar 1: profit 100
                order(1L, UserRole.EMPLOYEE, belex, OrderDirection.SELL, 1, "100.00"),
                // aktuar 2: profit 900
                order(2L, UserRole.EMPLOYEE, belex, OrderDirection.SELL, 9, "100.00")));
        mockEmployee(1L, "Low", "Profit", "low@test.com", List.of("AGENT"));
        mockEmployee(2L, "High", "Profit", "high@test.com", List.of("SUPERVISOR"));

        List<ActuaryProfitDto> result = service.listAllActuariesProfit();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getEmployeeId()).isEqualTo(2L); // 900 RSD prvi
        assertThat(result.get(1).getEmployeeId()).isEqualTo(1L); // 100 RSD drugi
    }

    @Test
    void adminPermission_resolvesToSupervisorPosition() {
        Listing belex = listing(6L, "BELEX");
        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(
                order(3L, UserRole.EMPLOYEE, belex, OrderDirection.SELL, 1, "100.00")));
        mockEmployee(3L, "Adm", "Inistrator", "admin@test.com", List.of("ADMIN"));

        List<ActuaryProfitDto> result = service.listAllActuariesProfit();

        assertThat(result.get(0).getPosition()).isEqualTo("SUPERVISOR");
    }

    @Test
    void actuarNoLongerInBankaCore_isExcludedFromList() {
        Listing belex = listing(7L, "BELEX");
        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(
                order(404L, UserRole.EMPLOYEE, belex, OrderDirection.SELL, 1, "100.00"),
                order(5L, UserRole.EMPLOYEE, belex, OrderDirection.SELL, 2, "100.00")));
        // 404 -> banka-core baca exception (zaposleni vise ne postoji)
        when(bankaCoreClient.getUserById(UserRole.EMPLOYEE, 404L))
                .thenThrow(new BankaCoreClientException(404, "not found"));
        mockEmployee(5L, "Still", "Here", "still@test.com", List.of("AGENT"));

        List<ActuaryProfitDto> result = service.listAllActuariesProfit();

        // samo aktuar #5 ostaje; #404 izostavljen
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEmployeeId()).isEqualTo(5L);
    }

    @Test
    void permissionsLookupFailure_fallsBackToAgentPosition() {
        Listing belex = listing(8L, "BELEX");
        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(
                order(6L, UserRole.EMPLOYEE, belex, OrderDirection.SELL, 1, "100.00")));
        when(bankaCoreClient.getUserById(UserRole.EMPLOYEE, 6L)).thenReturn(
                new InternalUserDto(6L, "EMPLOYEE", "x@test.com", "Perm", "Fail", true, "Agent"));
        // permisije lookup pada -> graceful AGENT default
        when(bankaCoreClient.getUserPermissions("x@test.com"))
                .thenThrow(new BankaCoreClientException(500, "boom"));

        List<ActuaryProfitDto> result = service.listAllActuariesProfit();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPosition()).isEqualTo("AGENT");
    }

    @Test
    void nullPricePerUnit_treatedAsZero() {
        Listing belex = listing(9L, "BELEX");
        // BUY sa null cenom -> 0 cost; SELL 1 @ 100 -> 100 value; profit 100
        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(
                order(12L, UserRole.EMPLOYEE, belex, OrderDirection.BUY, 5, null),
                order(12L, UserRole.EMPLOYEE, belex, OrderDirection.SELL, 1, "100.00")));
        mockEmployee(12L, "Null", "Price", "null@test.com", List.of("AGENT"));

        List<ActuaryProfitDto> result = service.listAllActuariesProfit();

        assertThat(result.get(0).getTotalProfitRsd()).isEqualByComparingTo("100.00");
    }

    @Test
    void fxConversionFailure_excludesLegFromRsdTotal_noCurrencyMixing() {
        // R1 509: FX fail -> taj leg se ISKLJUCUJE iz RSD sume (sa indikacijom u logu),
        // NE sabira se sirov USD broj kao RSD (sto je davalo pogresan, valutno-pomesan total).
        Listing nasdaq = listing(10L, "NASDAQ"); // USD
        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(
                order(13L, UserRole.EMPLOYEE, nasdaq, OrderDirection.SELL, 3, "100.00")));
        mockEmployee(13L, "Fx", "Fail", "fx@test.com", List.of("AGENT"));
        // 300 USD realizovan; konverzija puca -> leg iskljucen, total ostaje 0 (ne 300).
        when(currencyConversionService.convert(eq(new BigDecimal("300.00")), eq("USD"), eq("RSD")))
                .thenThrow(new RuntimeException("exchange unreachable"));

        List<ActuaryProfitDto> result = service.listAllActuariesProfit();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTotalProfitRsd())
                .as("FX-fail leg iskljucen iz RSD totala (ne sabira se sirov USD kao RSD)")
                .isEqualByComparingTo("0.00");
    }

    // ── R1 507: FIFO realized P/L — otvorena pozicija NE pravi lazni gubitak ──

    @Test
    void buyOnlyOpenPosition_yieldsZeroRealized_notFalseLoss() {
        // R1 507 KLJUC: otvorena DUGA pozicija (samo BUY, jos neprodato) =
        // 0 realizovan profit, NE -1000 (stari Σ SELL − Σ BUY je davao lazni gubitak).
        Listing belex = listing(20L, "BELEX"); // RSD
        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(
                order(21L, UserRole.EMPLOYEE, belex, OrderDirection.BUY, 10, "100.00")));
        mockEmployee(21L, "Open", "Long", "open@test.com", List.of("AGENT"));

        List<ActuaryProfitDto> result = service.listAllActuariesProfit();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTotalProfitRsd())
                .as("BUY-only otvorena pozicija = 0 realizovan, NE lazni gubitak")
                .isEqualByComparingTo("0.00");
        assertThat(result.get(0).getOrdersDone()).isEqualTo(1);
    }

    @Test
    void partiallyClosedPosition_realizesOnlyMatchedLot() {
        // BUY 10 @ 100, SELL 4 @ 150 -> realizovano samo na 4 sparene: (150-100)*4 = 200.
        // Ostatak 6 BUY ostaje otvoren -> 0 (NE -600 gubitak).
        Listing belex = listing(22L, "BELEX"); // RSD
        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(
                order(23L, UserRole.EMPLOYEE, belex, OrderDirection.BUY, 10, "100.00"),
                order(23L, UserRole.EMPLOYEE, belex, OrderDirection.SELL, 4, "150.00")));
        mockEmployee(23L, "Part", "Closed", "part@test.com", List.of("AGENT"));

        List<ActuaryProfitDto> result = service.listAllActuariesProfit();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTotalProfitRsd())
                .as("Realizovan samo sparen lot (4×50=200); otvoreni ostatak ne pravi gubitak")
                .isEqualByComparingTo("200.00");
    }

    @Test
    void fifoMatching_usesOldestBuyLotFirst() {
        // BUY 5 @ 100 (stariji), BUY 5 @ 200 (noviji), SELL 5 @ 150.
        // FIFO: SELL sparuje najstariji BUY (100) -> (150-100)*5 = 250.
        // (Da koristi noviji BUY @200 bilo bi -250 — pogresno.)
        // Ostatak BUY 5 @ 200 otvoren -> 0.
        Listing belex = listing(24L, "BELEX"); // RSD
        Order olderBuy = order(25L, UserRole.EMPLOYEE, belex, OrderDirection.BUY, 5, "100.00");
        olderBuy.setCreatedAt(java.time.LocalDateTime.of(2026, 1, 1, 9, 0));
        olderBuy.setId(1L);
        Order newerBuy = order(25L, UserRole.EMPLOYEE, belex, OrderDirection.BUY, 5, "200.00");
        newerBuy.setCreatedAt(java.time.LocalDateTime.of(2026, 1, 2, 9, 0));
        newerBuy.setId(2L);
        Order sell = order(25L, UserRole.EMPLOYEE, belex, OrderDirection.SELL, 5, "150.00");
        sell.setCreatedAt(java.time.LocalDateTime.of(2026, 1, 3, 9, 0));
        sell.setId(3L);
        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(newerBuy, sell, olderBuy));
        mockEmployee(25L, "Fifo", "Order", "fifo@test.com", List.of("AGENT"));

        List<ActuaryProfitDto> result = service.listAllActuariesProfit();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTotalProfitRsd())
                .as("FIFO: SELL sparuje najstariji BUY (100), ne noviji (200)")
                .isEqualByComparingTo("250.00");
    }
}
