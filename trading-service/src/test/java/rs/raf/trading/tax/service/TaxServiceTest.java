package rs.raf.trading.tax.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2.contracts.internal.InterbankOtcExercisedDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.banka2.contracts.internal.TaxCollectRequest;
import rs.raf.banka2.contracts.internal.TaxCollectResponse;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.otc.model.OtcContractStatus;
import rs.raf.trading.otc.repository.OtcContractRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;
import rs.raf.trading.tax.dto.TaxRecordDto;
import rs.raf.trading.tax.model.TaxRecord;
import rs.raf.trading.tax.repository.TaxRecordBreakdownRepository;
import rs.raf.trading.tax.repository.TaxRecordRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for TaxService covering:
 * - calculateTaxForAllUsers (15% on profits, no tax on loss)
 * - getTaxRecords with filters
 * - getMyTaxRecord for employee and client
 * - Mixed buy/sell orders
 * - Update existing tax record
 *
 * NAPOMENA (faza 2c): monolitni test je razresavao identitet preko
 * {@code UserRepository}/{@code EmployeeRepository} i naplacivao porez direktno
 * preko {@code AccountRepository}. trading-service razresava identitet preko
 * {@link BankaCoreClient#getUserById}/{@link BankaCoreClient#getUserByEmail} i
 * naplacuje porez preko {@link BankaCoreClient#collectTax}. {@code getTaxRecords}
 * i {@code calculateTaxForAllUsers} (obracun) su cista trading-service logika —
 * pokrivenost ostaje verno.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("TaxService")
class TaxServiceTest {

    @Mock private TaxRecordRepository taxRecordRepository;
    @Mock private TaxRecordBreakdownRepository taxRecordBreakdownRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private CurrencyConversionService currencyConversionService;
    @Mock private OtcContractRepository otcContractRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private BankaCoreClient bankaCoreClient;

    @InjectMocks
    private TaxService taxService;

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private Order buildOrder(Long userId, String role, OrderDirection dir,
                              BigDecimal pricePerUnit, int qty, int contractSize) {
        return buildOrderOfType(userId, role, dir, pricePerUnit, qty, contractSize, ListingType.STOCK);
    }

    private Order buildOrderOfType(Long userId, String role, OrderDirection dir,
                                   BigDecimal pricePerUnit, int qty, int contractSize,
                                   ListingType listingType) {
        Order o = new Order();
        o.setId((long) (Math.random() * 10000));
        o.setUserId(userId);
        o.setUserRole(role);
        o.setDirection(dir);
        o.setPricePerUnit(pricePerUnit);
        o.setQuantity(qty);
        o.setContractSize(contractSize);
        o.setDone(true);
        o.setStatus(OrderStatus.DONE);
        Listing listing = new Listing();
        // Spec (Celina 3 - Porez): porez se racuna samo na prodaju AKCIJA
        listing.setListingType(listingType);
        // Osnovni test-orderi se vode u RSD da bi izbegli konverziju —
        // testovi koji se bave FX-om eksplicitno setuju drugaciju valutu.
        listing.setQuoteCurrency("RSD");
        o.setListing(listing);
        return o;
    }

    private InternalUserDto user(Long id, String role, String firstName, String lastName) {
        return new InternalUserDto(id, role, "u" + id + "@test.com", firstName, lastName, true, null);
    }

    // ─── calculateTaxForAllUsers ────────────────────────────────────────────────

    @Nested
    @DisplayName("calculateTaxForAllUsers")
    class CalculateTaxForAll {

        @Test
        @DisplayName("15% tax on net profit when sell > buy")
        void taxOnPositiveProfit() {
            Order buy = buildOrder(1L, "CLIENT", OrderDirection.BUY, new BigDecimal("100"), 10, 1);
            Order sell = buildOrder(1L, "CLIENT", OrderDirection.SELL, new BigDecimal("150"), 10, 1);

            when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(buy, sell));
            when(bankaCoreClient.getUserById("CLIENT", 1L))
                    .thenReturn(user(1L, "CLIENT", "Marko", "Petrovic"));
            when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository).save(captor.capture());
            TaxRecord record = captor.getValue();

            // profit = sell(1500) - buy(1000) = 500
            assertThat(record.getTotalProfit()).isEqualByComparingTo(new BigDecimal("500"));
            // tax = 500 * 0.15 = 75
            assertThat(record.getTaxOwed()).isEqualByComparingTo(new BigDecimal("75.0000"));
            assertThat(record.getUserType()).isEqualTo("CLIENT");
        }

        @Test
        @DisplayName("R2-1447: FUTURES se oporezuje kao akcija (po pricePerUnit) — TAXABLE_LISTING_TYPES ukljucuje FUTURES")
        void futuresTaxedAsStockByPricePerUnit() {
            // Profesorovo pojasnjenje (TaxService javadoc): FUTURES se u nasem sistemu
            // tretira kao stock (ne hendlamo fizicko dospece) — porez = 15% na
            // (sell − buy) × pricePerUnit, identicno akciji. Pin: FUTURES order daje
            // isti poreski rezultat kao STOCK order sa istim cenama/kolicinama.
            Order buy = buildOrderOfType(2L, "CLIENT", OrderDirection.BUY,
                    new BigDecimal("100"), 10, 1, ListingType.FUTURES);
            Order sell = buildOrderOfType(2L, "CLIENT", OrderDirection.SELL,
                    new BigDecimal("150"), 10, 1, ListingType.FUTURES);

            when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(buy, sell));
            when(bankaCoreClient.getUserById("CLIENT", 2L))
                    .thenReturn(user(2L, "CLIENT", "Jovan", "Jovanovic"));
            when(taxRecordRepository.findByUserIdAndUserType(2L, "CLIENT")).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository).save(captor.capture());
            TaxRecord record = captor.getValue();

            // profit = sell(1500) − buy(1000) = 500; tax = 500 × 0.15 = 75 (kao stock).
            assertThat(record.getTotalProfit()).isEqualByComparingTo(new BigDecimal("500"));
            assertThat(record.getTaxOwed()).isEqualByComparingTo(new BigDecimal("75.0000"));
        }

        @Test
        @DisplayName("no tax when loss (sell < buy)")
        void noTaxOnLoss() {
            Order buy = buildOrder(1L, "CLIENT", OrderDirection.BUY, new BigDecimal("200"), 10, 1);
            Order sell = buildOrder(1L, "CLIENT", OrderDirection.SELL, new BigDecimal("100"), 10, 1);

            when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(buy, sell));
            when(bankaCoreClient.getUserById("CLIENT", 1L))
                    .thenReturn(user(1L, "CLIENT", "Marko", "Petrovic"));
            when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository).save(captor.capture());
            TaxRecord record = captor.getValue();

            // profit = sell(1000) - buy(2000) = -1000
            assertThat(record.getTotalProfit()).isNegative();
            assertThat(record.getTaxOwed()).isEqualByComparingTo(BigDecimal.ZERO);
            // gubitak → naplata se ne pokrece
            verify(bankaCoreClient, never()).collectTax(any(), any());
        }

        @Test
        @DisplayName("no tax when break even")
        void noTaxOnBreakEven() {
            Order buy = buildOrder(1L, "CLIENT", OrderDirection.BUY, new BigDecimal("100"), 10, 1);
            Order sell = buildOrder(1L, "CLIENT", OrderDirection.SELL, new BigDecimal("100"), 10, 1);

            when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(buy, sell));
            when(bankaCoreClient.getUserById("CLIENT", 1L))
                    .thenReturn(user(1L, "CLIENT", "Marko", "Petrovic"));
            when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository).save(captor.capture());

            assertThat(captor.getValue().getTaxOwed()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("BE-ORD-06: EMPLOYEE orders are NOT taxed (bank actuaries dont pay personal capital gains)")
        void employeeOrdersAreSkipped() {
            // Spec Celina 3 Sc 58: bank actuaries trade off bank accounts and dont pay
            // personal capital gains tax. Pre BE-ORD-06 fix, EMPLOYEE orders falsely
            // accumulated into personal TaxRecord. Sad se filtriraju u TaxService.
            Order sell = buildOrder(5L, "EMPLOYEE", OrderDirection.SELL, new BigDecimal("200"), 10, 1);

            when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(sell));
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(Collections.emptyList());

            taxService.calculateTaxForAllUsers();

            // EMPLOYEE order je preskocen → ni jedan TaxRecord se ne snima.
            verify(taxRecordRepository, never()).save(any());
        }

        @Test
        @DisplayName("updates existing tax record instead of creating new one")
        void updatesExistingRecord() {
            Order sell = buildOrder(1L, "CLIENT", OrderDirection.SELL, new BigDecimal("300"), 5, 1);

            TaxRecord existingRecord = TaxRecord.builder()
                    .id(10L)
                    .userId(1L)
                    .userType("CLIENT")
                    .totalProfit(new BigDecimal("100"))
                    .taxOwed(new BigDecimal("15"))
                    .taxPaid(BigDecimal.ZERO)
                    .currency("RSD")
                    .build();

            when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(sell));
            when(bankaCoreClient.getUserById("CLIENT", 1L))
                    .thenReturn(user(1L, "CLIENT", "Marko", "P"));
            when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.of(existingRecord));
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            verify(taxRecordRepository).save(existingRecord);
            // profit = 1500 (only sells, no buys)
            assertThat(existingRecord.getTotalProfit()).isEqualByComparingTo(new BigDecimal("1500"));
        }

        @Test
        @DisplayName("only done orders are processed")
        void onlyDoneOrders() {
            Order doneOrder = buildOrder(1L, "CLIENT", OrderDirection.SELL, new BigDecimal("100"), 10, 1);
            doneOrder.setDone(true);

            // findByIsDoneTrue returns only done orders — pending orders are filtered by the repository
            when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(doneOrder));
            when(bankaCoreClient.getUserById("CLIENT", 1L))
                    .thenReturn(user(1L, "CLIENT", "M", "P"));
            when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository).save(captor.capture());

            // Only the done sell order counted, no buy -> profit = 1000
            assertThat(captor.getValue().getTotalProfit()).isEqualByComparingTo(new BigDecimal("1000"));
        }

        @Test
        @DisplayName("no orders means no tax records saved")
        void noOrders() {
            when(orderRepository.findByIsDoneTrue()).thenReturn(Collections.emptyList());
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(Collections.emptyList());

            taxService.calculateTaxForAllUsers();

            verify(taxRecordRepository, never()).save(any());
        }

        @Test
        @DisplayName("multiple CLIENT users are processed independently (BE-ORD-06: EMPLOYEE skipped)")
        void multipleUsers() {
            // BE-ORD-06: pre fix-a su CLIENT + EMPLOYEE orderi oba davali TaxRecord.
            // Sad samo CLIENT orderi padaju u personal tax obracun (bank actuaries
            // ne placaju licni porez; Profit Banke portal pokriva bank profit).
            Order user1Sell = buildOrder(1L, "CLIENT", OrderDirection.SELL, new BigDecimal("100"), 10, 1);
            Order user2Sell = buildOrder(2L, "CLIENT", OrderDirection.SELL, new BigDecimal("50"), 20, 1);

            when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(user1Sell, user2Sell));
            when(bankaCoreClient.getUserById("CLIENT", 1L))
                    .thenReturn(user(1L, "CLIENT", "A", "B"));
            when(bankaCoreClient.getUserById("CLIENT", 2L))
                    .thenReturn(user(2L, "CLIENT", "C", "D"));
            when(taxRecordRepository.findByUserIdAndUserType(any(), any())).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            verify(taxRecordRepository, times(2)).save(any(TaxRecord.class));
        }

        @Test
        @DisplayName("contractSize multiplies into total value")
        void contractSizeMultiplied() {
            // price=10, qty=5, contractSize=100 -> orderValue = 10*5*100 = 5000
            Order sell = buildOrder(1L, "CLIENT", OrderDirection.SELL, new BigDecimal("10"), 5, 100);

            when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(sell));
            when(bankaCoreClient.getUserById("CLIENT", 1L))
                    .thenReturn(user(1L, "CLIENT", "A", "B"));
            when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository).save(captor.capture());

            assertThat(captor.getValue().getTotalProfit()).isEqualByComparingTo(new BigDecimal("5000"));
        }
    }

    // ─── Currency conversion (S80) ──────────────────────────────────────────────

    @Nested
    @DisplayName("CurrencyConversion (S80)")
    class CurrencyConversion {

        private Listing listingWithCurrency(Long id, String quoteCurrency) {
            Listing l = new Listing();
            l.setId(id);
            l.setQuoteCurrency(quoteCurrency);
            // Porez se racuna samo za STOCK (Celina 3 spec)
            l.setListingType(ListingType.STOCK);
            return l;
        }

        private Order buildOrderWithListing(Long userId, Listing listing, OrderDirection dir,
                                            BigDecimal pricePerUnit, int qty) {
            Order o = new Order();
            o.setId((long) (Math.random() * 10000));
            o.setUserId(userId);
            o.setUserRole("CLIENT");
            o.setDirection(dir);
            o.setPricePerUnit(pricePerUnit);
            o.setQuantity(qty);
            o.setContractSize(1);
            o.setDone(true);
            o.setStatus(OrderStatus.DONE);
            o.setListing(listing);
            return o;
        }

        @Test
        @DisplayName("Porez se agregira u RSD iz mix valuta (S80)")
        void calculateTax_convertsMixedCurrencyProfitToRsd() {
            // user 1: 100 USD profit iz AAPL (BUY@100 x1, SELL@200 x1 => 100 USD)
            Listing aapl = listingWithCurrency(1L, "USD");
            Order aaplBuy = buildOrderWithListing(1L, aapl, OrderDirection.BUY, new BigDecimal("100"), 1);
            Order aaplSell = buildOrderWithListing(1L, aapl, OrderDirection.SELL, new BigDecimal("200"), 1);

            // user 1: 5000 RSD profit iz XYZ (BUY@1000 x1, SELL@6000 x1 => 5000 RSD)
            Listing xyz = listingWithCurrency(2L, "RSD");
            Order xyzBuy = buildOrderWithListing(1L, xyz, OrderDirection.BUY, new BigDecimal("1000"), 1);
            Order xyzSell = buildOrderWithListing(1L, xyz, OrderDirection.SELL, new BigDecimal("6000"), 1);

            when(orderRepository.findByIsDoneTrue())
                    .thenReturn(List.of(aaplBuy, aaplSell, xyzBuy, xyzSell));
            when(bankaCoreClient.getUserById("CLIENT", 1L))
                    .thenReturn(user(1L, "CLIENT", "Marko", "Petrovic"));
            when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Mock: 100 USD -> 10920 RSD (srednji kurs ~109.20)
            when(currencyConversionService.convert(new BigDecimal("100"), "USD", "RSD"))
                    .thenReturn(new BigDecimal("10920"));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository).save(captor.capture());
            TaxRecord record = captor.getValue();

            // totalProfit (u RSD) = 10920 (iz USD) + 5000 (RSD) = 15920
            assertThat(record.getTotalProfit()).isEqualByComparingTo(new BigDecimal("15920"));
            // taxOwed = 0.15 * 15920 = 2388
            assertThat(record.getTaxOwed()).isEqualByComparingTo(new BigDecimal("2388"));
            assertThat(record.getCurrency()).isEqualTo("RSD");

            // Verifikuj da je CurrencyConversionService pozvan za USD (ne i za RSD)
            verify(currencyConversionService).convert(new BigDecimal("100"), "USD", "RSD");
            verify(currencyConversionService, never()).convert(any(), eq("RSD"), eq("RSD"));
        }

        @Test
        @DisplayName("BE-ORD-08: FX failure baca TaxCalculationException (ne fallback-uje na raw amount)")
        void calculateTax_fxFailureThrowsTaxCalculationException() {
            // Pre fix-a, FX failure je tisko fallback-ovao na sirovi iznos (USD 1000
            // tretiran kao 1000 RSD = severe under-taxation). Sada propagira
            // TaxCalculationException pa scheduler obavestava supervizora.
            Listing aapl = listingWithCurrency(1L, "USD");
            Order aaplBuy = buildOrderWithListing(1L, aapl, OrderDirection.BUY, new BigDecimal("100"), 1);
            Order aaplSell = buildOrderWithListing(1L, aapl, OrderDirection.SELL, new BigDecimal("200"), 1);

            when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(aaplBuy, aaplSell));
            when(bankaCoreClient.getUserById("CLIENT", 1L))
                    .thenReturn(user(1L, "CLIENT", "Marko", "Petrovic"));
            when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());

            // FX service puca — banka-core /internal/fx/rates nedostupan
            when(currencyConversionService.convert(new BigDecimal("100"), "USD", "RSD"))
                    .thenThrow(new RuntimeException("banka-core /internal/fx/rates timeout"));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> taxService.calculateTaxForAllUsers())
                    .isInstanceOf(rs.raf.trading.tax.service.TaxCalculationException.class)
                    .hasMessageContaining("FX rate unavailable for USD");

            // KRITICNO: TaxRecord se NE snima (raw amount NE tretira kao RSD).
            verify(taxRecordRepository, never()).save(any());
        }
    }

    // ─── OTC EXERCISED tax (P2-tax-cost-basis-1: R1 430/431/432/R5 1901) ─────────

    @Nested
    @DisplayName("OTC EXERCISED tax (P2-tax-cost-basis-1)")
    class OtcExercisedTax {

        private Listing stockListing(Long id, String currency) {
            Listing l = new Listing();
            l.setId(id);
            l.setTicker("OTCX");
            l.setListingType(ListingType.STOCK);
            l.setQuoteCurrency(currency);
            return l;
        }

        private OtcContract otcContract(Long buyerId, String buyerRole, Long sellerId, String sellerRole,
                                        Listing listing, int qty, BigDecimal strike, BigDecimal premium) {
            OtcContract c = new OtcContract();
            c.setId((long) (Math.random() * 100000));
            c.setSourceOfferId(1L);
            c.setBuyerId(buyerId);
            c.setBuyerRole(buyerRole);
            c.setSellerId(sellerId);
            c.setSellerRole(sellerRole);
            c.setListing(listing);
            c.setQuantity(qty);
            c.setStrikePrice(strike);
            c.setPremium(premium);
            c.setStatus(OtcContractStatus.EXERCISED);
            return c;
        }

        @Test
        @DisplayName("R5 1901: tax run loads EXERCISED contracts via DB query, not findAll()")
        void usesExercisedQueryNotFindAll() {
            when(orderRepository.findByIsDoneTrue()).thenReturn(Collections.emptyList());
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(Collections.emptyList());

            taxService.calculateTaxForAllUsers();

            verify(otcContractRepository).findExercisedStockContracts();
            verify(otcContractRepository, never()).findAll();
        }

        @Test
        @DisplayName("R1 432: SELLER proceeds = strike + premium (premium is seller income)")
        void sellerProceedsIncludePremium() {
            // seller (client 7) sold an OTC option; at exercise delivers 10 shares @ strike 100,
            // received premium 150. Proceeds (no FIFO cost-basis — accepted P0-B3 deviation) =
            // strike*qty + premium = 1000 + 150 = 1150. Tax = 15% * 1150 = 172.50.
            Listing aapl = stockListing(50L, "RSD");
            OtcContract c = otcContract(99L, "CLIENT", 7L, "CLIENT", aapl, 10,
                    new BigDecimal("100"), new BigDecimal("150"));

            when(orderRepository.findByIsDoneTrue()).thenReturn(Collections.emptyList());
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(List.of(c));
            when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(user(7L, "CLIENT", "Sel", "Ler"));
            when(bankaCoreClient.getUserById("CLIENT", 99L)).thenReturn(user(99L, "CLIENT", "Buy", "Er"));
            when(taxRecordRepository.findByUserIdAndUserType(any(), any())).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository, atLeastOnce()).save(captor.capture());
            TaxRecord sellerRecord = captor.getAllValues().stream()
                    .filter(r -> r.getUserId().equals(7L)).findFirst().orElseThrow();
            assertThat(sellerRecord.getTotalProfit()).isEqualByComparingTo(new BigDecimal("1150"));
            assertThat(sellerRecord.getTaxOwed()).isEqualByComparingTo(new BigDecimal("172.5000"));
        }

        @Test
        @DisplayName("R1 432: BUYER acquiring shares is NOT a realized loss (premium not double-counted)")
        void buyerAcquisitionNotRealizedLoss() {
            // buyer (client 99) exercised: pays strike+premium, acquires shares. Acquiring is
            // NOT a realized capital event → buyer's OTC contribution to CURRENT-period gain is
            // zero (no fictitious loss). Pre-fix the premium was also booked on the buy side.
            Listing aapl = stockListing(50L, "RSD");
            OtcContract c = otcContract(99L, "CLIENT", 7L, "CLIENT", aapl, 10,
                    new BigDecimal("100"), new BigDecimal("150"));

            when(orderRepository.findByIsDoneTrue()).thenReturn(Collections.emptyList());
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(List.of(c));
            when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(user(7L, "CLIENT", "Sel", "Ler"));
            when(bankaCoreClient.getUserById("CLIENT", 99L)).thenReturn(user(99L, "CLIENT", "Buy", "Er"));
            when(taxRecordRepository.findByUserIdAndUserType(any(), any())).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository, atLeastOnce()).save(captor.capture());
            TaxRecord buyerRecord = captor.getAllValues().stream()
                    .filter(r -> r.getUserId().equals(99L)).findFirst().orElseThrow();
            // buyer has no realized gain/loss this period (acquisition only)
            assertThat(buyerRecord.getTotalProfit()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(buyerRecord.getTaxOwed()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("R1 432: same user buyer+seller on same listing — premium counted exactly once (no double-count)")
        void sameUserBuyerAndSeller_premiumNotDoubleCounted() {
            // User 7 is SELLER of contract A (delivers, gets strike+premium income) AND BUYER of
            // contract B on the SAME listing (acquires at strike, pays premium to its seller).
            // Pre-fix: buy side carried strike+premiumB, so user-7 net = (strikeA+premiumA) −
            // (strikeB+premiumB) → premiumB (paid to someone else) wrongly reduced user-7's gain
            // = premium double-counted across the system (under-taxation). Post-fix: buy side
            // carries ONLY strikeB → premium appears exactly once (on each seller's proceeds).
            Listing aapl = stockListing(50L, "RSD");
            // contract A: user 7 sells 10 @ strike 100, premium 150  → seller proceeds 1150
            OtcContract a = otcContract(99L, "CLIENT", 7L, "CLIENT", aapl, 10,
                    new BigDecimal("100"), new BigDecimal("150"));
            // contract B: user 7 buys 4 @ strike 100, premium 80    → buyer cost-basis 400 (NO premium)
            OtcContract b = otcContract(7L, "CLIENT", 88L, "CLIENT", aapl, 4,
                    new BigDecimal("100"), new BigDecimal("80"));

            when(orderRepository.findByIsDoneTrue()).thenReturn(Collections.emptyList());
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(List.of(a, b));
            when(bankaCoreClient.getUserById(eq("CLIENT"), any())).thenAnswer(inv ->
                    user(inv.getArgument(1), "CLIENT", "U", "ser"));
            when(taxRecordRepository.findByUserIdAndUserType(any(), any())).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository, atLeastOnce()).save(captor.capture());
            TaxRecord u7 = captor.getAllValues().stream()
                    .filter(r -> r.getUserId().equals(7L)).findFirst().orElseThrow();
            // user-7 net on listing 50 = sell(strikeA 1000 + premiumA 150) − buy(strikeB 400) = 750.
            // premiumB (80) is NOT subtracted here — it belongs only to contract-B seller's proceeds.
            assertThat(u7.getTotalProfit()).isEqualByComparingTo(new BigDecimal("750"));
            assertThat(u7.getTaxOwed()).isEqualByComparingTo(new BigDecimal("112.5000"));
        }

        @Test
        @DisplayName("R1 431: EXERCISED contract counted exactly once per side (intra-bank only, no inter dup)")
        void exercisedContractCountedOncePerSide() {
            // Only intra-bank contracts live in otc_contracts. A single EXERCISED contract must
            // produce exactly one seller TaxRecord and one buyer TaxRecord — never twice.
            Listing aapl = stockListing(50L, "RSD");
            OtcContract c = otcContract(99L, "CLIENT", 7L, "CLIENT", aapl, 10,
                    new BigDecimal("100"), new BigDecimal("150"));

            when(orderRepository.findByIsDoneTrue()).thenReturn(Collections.emptyList());
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(List.of(c));
            when(bankaCoreClient.getUserById(eq("CLIENT"), any())).thenAnswer(inv ->
                    user(inv.getArgument(1), "CLIENT", "U", "ser"));
            when(taxRecordRepository.findByUserIdAndUserType(any(), any())).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            // exactly 2 saves: one for seller (7), one for buyer (99)
            verify(taxRecordRepository, times(2)).save(any(TaxRecord.class));
        }

        @Test
        @DisplayName("R1 431/BE-ORD-06: EMPLOYEE OTC participant is excluded (bank doesn't pay personal tax)")
        void employeeOtcParticipantExcluded() {
            // seller is EMPLOYEE (bank actuary) → not personally taxed; buyer is CLIENT → only
            // the buyer side may be considered, and buyer acquisition is not a realized event →
            // no TaxRecord saved at all.
            Listing aapl = stockListing(50L, "RSD");
            OtcContract c = otcContract(99L, "CLIENT", 5L, "EMPLOYEE", aapl, 10,
                    new BigDecimal("100"), new BigDecimal("150"));

            when(orderRepository.findByIsDoneTrue()).thenReturn(Collections.emptyList());
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(List.of(c));
            when(bankaCoreClient.getUserById("CLIENT", 99L)).thenReturn(user(99L, "CLIENT", "Buy", "Er"));
            when(taxRecordRepository.findByUserIdAndUserType(any(), any())).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            // EMPLOYEE seller skipped; buyer has no realized gain → still saves buyer record with 0
            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository, atMost(1)).save(captor.capture());
            // never a record for the EMPLOYEE seller (id 5)
            assertThat(captor.getAllValues().stream().noneMatch(r -> r.getUserId().equals(5L))).isTrue();
        }
    }

    // ─── Inter-bank OTC EXERCISED tax (P2-tax-interbank-otc-1) ──────────────────

    @Nested
    @DisplayName("Inter-bank OTC EXERCISED tax (P2-tax-interbank-otc-1)")
    class InterbankOtcExercisedTax {

        private Listing stockListing(Long id, String currency, String ticker) {
            Listing l = new Listing();
            l.setId(id);
            l.setTicker(ticker);
            l.setListingType(ListingType.STOCK);
            l.setQuoteCurrency(currency);
            return l;
        }

        private InterbankOtcExercisedDto interbankContract(Long id, Long localPartyId,
                                                           String localPartyRole, String localPartyType,
                                                           String ticker, String qty, String strike,
                                                           String premium) {
            return new InterbankOtcExercisedDto(id, localPartyId, localPartyRole, localPartyType,
                    ticker, new BigDecimal(qty), new BigDecimal(strike), "RSD",
                    new BigDecimal(premium), "RSD", LocalDateTime.now());
        }

        @Test
        @DisplayName("GAP→FIX: local CLIENT SELLER of inter-bank OTC is taxed (was taxed NOWHERE)")
        void interbankSellerClient_isTaxedOnceAt15Percent() {
            // Pre-fix: inter-bank OTC EXERCISED was visible only to banka-core (no tax module)
            // and never to the trading-service tax engine → local CLIENT realized capital gain
            // taxed NOWHERE (under-taxation). Post-fix: seller proceeds = strike*qty + premium,
            // taxed at 15% exactly once.
            Listing aapl = stockListing(50L, "RSD", "AAPL");
            // local client 7 is SELLER: delivers 10 @ strike 100, received premium 150 → proceeds 1150
            InterbankOtcExercisedDto c = interbankContract(1L, 7L, "CLIENT", "SELLER",
                    "AAPL", "10", "100", "150");

            when(orderRepository.findByIsDoneTrue()).thenReturn(Collections.emptyList());
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(Collections.emptyList());
            when(bankaCoreClient.getExercisedInterbankOtc()).thenReturn(List.of(c));
            when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(aapl));
            when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(user(7L, "CLIENT", "Sel", "Ler"));
            when(taxRecordRepository.findByUserIdAndUserType(any(), any())).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository).save(captor.capture());
            TaxRecord sellerRecord = captor.getValue();
            assertThat(sellerRecord.getUserId()).isEqualTo(7L);
            // proceeds = strike*qty + premium = 1000 + 150 = 1150; tax = 15% = 172.50
            assertThat(sellerRecord.getTotalProfit()).isEqualByComparingTo(new BigDecimal("1150"));
            assertThat(sellerRecord.getTaxOwed()).isEqualByComparingTo(new BigDecimal("172.5000"));
        }

        @Test
        @DisplayName("buyer cost-basis = strike*qty (premium NOT on buyer side — mirror intra R1-432)")
        void interbankBuyerClient_costBasisExcludesPremium() {
            // local client 99 is BUYER: acquires shares (not a realized event) → 0 gain this period.
            // premium must NOT be booked on buyer side (mirror intra-OTC R1-432).
            Listing aapl = stockListing(50L, "RSD", "AAPL");
            InterbankOtcExercisedDto c = interbankContract(1L, 99L, "CLIENT", "BUYER",
                    "AAPL", "10", "100", "150");

            when(orderRepository.findByIsDoneTrue()).thenReturn(Collections.emptyList());
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(Collections.emptyList());
            when(bankaCoreClient.getExercisedInterbankOtc()).thenReturn(List.of(c));
            when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(aapl));
            when(bankaCoreClient.getUserById("CLIENT", 99L)).thenReturn(user(99L, "CLIENT", "Buy", "Er"));
            when(taxRecordRepository.findByUserIdAndUserType(any(), any())).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository).save(captor.capture());
            TaxRecord buyerRecord = captor.getValue();
            assertThat(buyerRecord.getUserId()).isEqualTo(99L);
            assertThat(buyerRecord.getTotalProfit()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(buyerRecord.getTaxOwed()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("BE-ORD-06: EMPLOYEE inter-bank OTC participant is excluded (no personal tax)")
        void interbankEmployeeSeller_excluded() {
            Listing aapl = stockListing(50L, "RSD", "AAPL");
            // local EMPLOYEE 5 is SELLER → bank actuary, not personally taxed
            InterbankOtcExercisedDto c = interbankContract(1L, 5L, "EMPLOYEE", "SELLER",
                    "AAPL", "10", "100", "150");

            when(orderRepository.findByIsDoneTrue()).thenReturn(Collections.emptyList());
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(Collections.emptyList());
            when(bankaCoreClient.getExercisedInterbankOtc()).thenReturn(List.of(c));
            when(taxRecordRepository.findByUserIdAndUserType(any(), any())).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            // EMPLOYEE skipped → no record saved, ticker never resolved
            verify(taxRecordRepository, never()).save(any());
            verify(listingRepository, never()).findByTicker(any());
        }

        @Test
        @DisplayName("DEDUP: same user with BOTH intra AND inter exercised in same period → summed, no double-count")
        void intraAndInterSamePeriod_summedNoDoubleCount() {
            // User 7 is SELLER on an INTRA contract (listing 50) AND SELLER on an INTER contract
            // (same ticker AAPL → same listing 50). Each must contribute its own proceeds exactly
            // once; they must SUM, not double-count. intra: strike 100 * 10 + premium 150 = 1150;
            // inter: strike 200 * 5 + premium 50 = 1050. Total = 2200; tax = 15% = 330.
            Listing aapl = stockListing(50L, "RSD", "AAPL");
            OtcContract intra = new OtcContract();
            intra.setId(123L);
            intra.setSourceOfferId(1L);
            intra.setBuyerId(99L);
            intra.setBuyerRole("CLIENT");
            intra.setSellerId(7L);
            intra.setSellerRole("CLIENT");
            intra.setListing(aapl);
            intra.setQuantity(10);
            intra.setStrikePrice(new BigDecimal("100"));
            intra.setPremium(new BigDecimal("150"));
            intra.setStatus(OtcContractStatus.EXERCISED);

            InterbankOtcExercisedDto inter = interbankContract(1L, 7L, "CLIENT", "SELLER",
                    "AAPL", "5", "200", "50");

            when(orderRepository.findByIsDoneTrue()).thenReturn(Collections.emptyList());
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(List.of(intra));
            when(bankaCoreClient.getExercisedInterbankOtc()).thenReturn(List.of(inter));
            when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(aapl));
            when(bankaCoreClient.getUserById(eq("CLIENT"), any())).thenAnswer(inv ->
                    user(inv.getArgument(1), "CLIENT", "U", "ser"));
            when(taxRecordRepository.findByUserIdAndUserType(any(), any())).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository, atLeastOnce()).save(captor.capture());
            TaxRecord u7 = captor.getAllValues().stream()
                    .filter(r -> r.getUserId().equals(7L)).findFirst().orElseThrow();
            // intra proceeds 1150 + inter proceeds 1050 = 2200 (summed once each, no double-count)
            assertThat(u7.getTotalProfit()).isEqualByComparingTo(new BigDecimal("2200"));
            assertThat(u7.getTaxOwed()).isEqualByComparingTo(new BigDecimal("330.0000"));
            // user 7 saved exactly once (single aggregated TaxRecord, not one per contract)
            long u7Saves = captor.getAllValues().stream().filter(r -> r.getUserId().equals(7L)).count();
            assertThat(u7Saves).isEqualTo(1L);
        }

        @Test
        @DisplayName("unknown ticker (no trading listing) is skipped, not crashed")
        void interbankUnknownTicker_skipped() {
            InterbankOtcExercisedDto c = interbankContract(1L, 7L, "CLIENT", "SELLER",
                    "GHOST", "10", "100", "150");

            when(orderRepository.findByIsDoneTrue()).thenReturn(Collections.emptyList());
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(Collections.emptyList());
            when(bankaCoreClient.getExercisedInterbankOtc()).thenReturn(List.of(c));
            when(listingRepository.findByTicker("GHOST")).thenReturn(Optional.empty());
            when(taxRecordRepository.findByUserIdAndUserType(any(), any())).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            // no listing → seller has no taxable listing contribution → no record saved
            verify(taxRecordRepository, never()).save(any());
        }

        @Test
        @DisplayName("banka-core unavailable for inter-bank fetch → intra tax still computed (best-effort)")
        void interbankFetchFails_intraStillComputed() {
            // If banka-core /internal/interbank-otc/exercised is down, the tax run must not abort
            // the whole batch — intra OTC + orders still get taxed (resilient, like notifications).
            Listing aapl = stockListing(50L, "RSD", "AAPL");
            OtcContract intra = new OtcContract();
            intra.setId(123L);
            intra.setSourceOfferId(1L);
            intra.setBuyerId(99L);
            intra.setBuyerRole("CLIENT");
            intra.setSellerId(7L);
            intra.setSellerRole("CLIENT");
            intra.setListing(aapl);
            intra.setQuantity(10);
            intra.setStrikePrice(new BigDecimal("100"));
            intra.setPremium(new BigDecimal("150"));
            intra.setStatus(OtcContractStatus.EXERCISED);

            when(orderRepository.findByIsDoneTrue()).thenReturn(Collections.emptyList());
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(List.of(intra));
            when(bankaCoreClient.getExercisedInterbankOtc())
                    .thenThrow(new rs.raf.trading.client.BankaCoreClientException(503, "down"));
            when(bankaCoreClient.getUserById(eq("CLIENT"), any())).thenAnswer(inv ->
                    user(inv.getArgument(1), "CLIENT", "U", "ser"));
            when(taxRecordRepository.findByUserIdAndUserType(any(), any())).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository, atLeastOnce()).save(captor.capture());
            TaxRecord u7 = captor.getAllValues().stream()
                    .filter(r -> r.getUserId().equals(7L)).findFirst().orElseThrow();
            // intra seller proceeds 1150 still computed
            assertThat(u7.getTotalProfit()).isEqualByComparingTo(new BigDecimal("1150"));
        }
    }

    // ─── P2-tax OTC period-scoping CRITICAL fix (01.06) ─────────────────────────

    /**
     * P2-tax OTC period-scoping (01.06) — reviewer-found CRITICAL over-taxation bug.
     *
     * <p><b>Bug:</b> OTC EXERCISED status je TRAJAN (ugovor ostaje EXERCISED zauvek u
     * tabeli). Pre fix-a {@code findExercisedStockContracts()} (intra) i
     * {@code getExercisedInterbankOtc()} (inter) su vracali SVE EXERCISED ugovore
     * globalno, a {@code addOtcNet} je dodavao OTC net u TEKUCI period BEZUSLOVNO.
     * Idempotency kljuc ukljucuje period → svaki mesecni run daje NOV kljuc → banka-core
     * {@code collectTaxIdempotent} radi SVEZ realan debit → isti jednokratni OTC dobitak
     * se re-oporezuje SVAKI mesec zauvek.
     *
     * <p><b>Fix (approach B — in-calculator/service period gate):</b> period-gate OTC
     * kontribucije po {@code exercisedAt} sa ISTIM {@code TaxRealizedGainCalculator.inPeriod}
     * gate-om koji order SELL leg vec koristi (mirror {@code fifoPeriodGain}). Ugovor
     * exercise-ovan u mesecu M oporezuje se SAMO u run-u koji settle-uje M, i doprinosi
     * 0 svakom kasnijem run-u.
     *
     * <p><b>Metod testiranja:</b> {@code calculateTaxForAllUsers()} koristi
     * {@code LocalDateTime.now()} (nije injektabilno), pa simuliramo "kasniji mesec
     * run" tako sto ugovoru postavimo {@code exercisedAt} u garantovano PROSLI mesec
     * (fiksni datum u 2020). Pre fix-a: ugovor se i dalje broji (RED — re-tax). Posle
     * fix-a: {@code inPeriod(exercisedAt=2020, period=now, nowMonth=now)} → false →
     * doprinosi 0 (GREEN). Tekuci-mesec ugovori (exercisedAt == now) ostaju oporezovani
     * jednom (postojeci single-run testovi). Drugi run je stateful TaxRecord (mesecni
     * paid bucket popunjen) — bez novog debita ni promene.
     */
    @Nested
    @DisplayName("OTC period-scoping CRITICAL (01.06) — exercised contract taxed ONLY in its settlement month")
    class OtcPeriodScoping {

        private Listing stockListing(Long id, String currency, String ticker) {
            Listing l = new Listing();
            l.setId(id);
            l.setTicker(ticker);
            l.setListingType(ListingType.STOCK);
            l.setQuoteCurrency(currency);
            return l;
        }

        private OtcContract intraExercised(Long buyerId, Long sellerId, Listing listing,
                                           int qty, String strike, String premium,
                                           LocalDateTime exercisedAt) {
            OtcContract c = new OtcContract();
            c.setId((long) (Math.random() * 100000));
            c.setSourceOfferId(1L);
            c.setBuyerId(buyerId);
            c.setBuyerRole("CLIENT");
            c.setSellerId(sellerId);
            c.setSellerRole("CLIENT");
            c.setListing(listing);
            c.setQuantity(qty);
            c.setStrikePrice(new BigDecimal(strike));
            c.setPremium(new BigDecimal(premium));
            c.setStatus(OtcContractStatus.EXERCISED);
            c.setExercisedAt(exercisedAt);
            return c;
        }

        private InterbankOtcExercisedDto interExercised(Long localPartyId, String localPartyType,
                                                        String ticker, String qty, String strike,
                                                        String premium, LocalDateTime exercisedAt) {
            return new InterbankOtcExercisedDto(1L, localPartyId, "CLIENT", localPartyType,
                    ticker, new BigDecimal(qty), new BigDecimal(strike), "RSD",
                    new BigDecimal(premium), "RSD", exercisedAt);
        }

        // ── INTRA multi-month ──────────────────────────────────────────────────

        /**
         * In-period stock SELL (createdAt == now, no BUY → proceeds = gain) used to keep the
         * seller in the run so {@code processOne} executes and recomputes the record. The OTC
         * leg must then contribute 0 (gated out by exercisedAt), so the only profit is this 1 RSD.
         */
        private Order currentMonthStockSell(Long userId, Listing listing, String price, int qty) {
            Order o = new Order();
            o.setId((long) (Math.random() * 100000));
            o.setUserId(userId);
            o.setUserRole("CLIENT");
            o.setDirection(OrderDirection.SELL);
            o.setPricePerUnit(new BigDecimal(price));
            o.setQuantity(qty);
            o.setContractSize(1);
            o.setDone(true);
            o.setStatus(OrderStatus.DONE);
            o.setListing(listing);
            o.setCreatedAt(LocalDateTime.now()); // current month → always in settlement period
            return o;
        }

        @Test
        @DisplayName("INTRA multi-month: contract exercised in a PRIOR month contributes 0 to the current run (RED before fix: adds 1150)")
        void intraExercisedPriorMonth_notReTaxed() {
            // Seller (client 7) exercised an INTRA OTC contract in a PRIOR month (2020-01) — it was
            // already taxed in the prior run (proceeds = strike*qty + premium = 1000 + 150 = 1150).
            // Seller also has a tiny CURRENT-month stock SELL (1 share @ 1 RSD, no buy → gain 1) so
            // the user is still processed this run. The current settlement run must NOT re-tax the
            // prior-month OTC: total profit must be 1 (stock only), NOT 1151.
            //   BEFORE FIX (RED): OTC net added unconditionally → totalProfit = 1 + 1150 = 1151.
            //   AFTER FIX (GREEN): exercisedAt(2020-01) ∉ current period → OTC contributes 0 → 1.
            Listing aapl = stockListing(50L, "RSD", "AAPL");
            OtcContract priorMonth = intraExercised(99L, 7L, aapl, 10, "100", "150",
                    LocalDateTime.of(2020, 1, 15, 10, 0));
            Order tinySell = currentMonthStockSell(7L, aapl, "1", 1); // gain = 1 RSD this period

            when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(tinySell));
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(List.of(priorMonth));
            // Buyer 99 stubbed leniently: on the FIXED path the prior-month contract is gated out
            // so user 99 is never processed; on the UNFIXED path user 99 IS processed (buyer side),
            // and stubbing makes the RED assertion a clean value comparison (1 vs 1151), not an NPE.
            when(bankaCoreClient.getUserById(eq("CLIENT"), any())).thenAnswer(inv ->
                    user(inv.getArgument(1), "CLIENT", "U", "ser"));
            when(taxRecordRepository.findByUserIdAndUserType(any(), any())).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(bankaCoreClient.collectTax(any(), any()))
                    .thenAnswer(inv -> new TaxCollectResponse(7L,
                            ((TaxCollectRequest) inv.getArgument(1)).amount(), true));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository, atLeastOnce()).save(captor.capture());
            TaxRecord seller = captor.getAllValues().stream()
                    .filter(r -> r.getUserId().equals(7L)).findFirst().orElseThrow();
            // ONLY the current-month stock gain (1), the prior-month OTC contributes 0.
            assertThat(seller.getTotalProfit()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(seller.getTaxOwed()).isEqualByComparingTo(new BigDecimal("0.1500"));
            // CRITICAL: the 172.50 OTC tax is NOT re-collected; only the tiny new stock tax (0.15).
            ArgumentCaptor<TaxCollectRequest> reqCap = ArgumentCaptor.forClass(TaxCollectRequest.class);
            verify(bankaCoreClient, times(1)).collectTax(any(), reqCap.capture());
            assertThat(reqCap.getValue().amount()).isEqualByComparingTo("0.1500");
        }

        @Test
        @DisplayName("INTRA first-run unchanged: contract exercised in the CURRENT month is taxed once")
        void intraExercisedCurrentMonth_taxedOnce() {
            // Sanity / no-regression: a contract exercised NOW (current settlement month) is still
            // taxed once at 15% (first-run behavior preserved).
            Listing aapl = stockListing(50L, "RSD", "AAPL");
            OtcContract currentMonth = intraExercised(99L, 7L, aapl, 10, "100", "150",
                    LocalDateTime.now());

            when(orderRepository.findByIsDoneTrue()).thenReturn(Collections.emptyList());
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(List.of(currentMonth));
            when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(user(7L, "CLIENT", "Sel", "Ler"));
            when(bankaCoreClient.getUserById("CLIENT", 99L)).thenReturn(user(99L, "CLIENT", "Buy", "Er"));
            when(taxRecordRepository.findByUserIdAndUserType(any(), any())).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(bankaCoreClient.collectTax(any(), any()))
                    .thenAnswer(inv -> new TaxCollectResponse(7L,
                            ((TaxCollectRequest) inv.getArgument(1)).amount(), true));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository, atLeastOnce()).save(captor.capture());
            TaxRecord seller = captor.getAllValues().stream()
                    .filter(r -> r.getUserId().equals(7L)).findFirst().orElseThrow();
            assertThat(seller.getTotalProfit()).isEqualByComparingTo(new BigDecimal("1150"));
            assertThat(seller.getTaxOwed()).isEqualByComparingTo(new BigDecimal("172.5000"));
            // taxed exactly once this run
            verify(bankaCoreClient, times(1)).collectTax(any(), any());
        }

        // ── INTER multi-month ──────────────────────────────────────────────────

        @Test
        @DisplayName("INTER multi-month: inter-bank contract exercised in a PRIOR month contributes 0 to the current run (RED before fix: adds 1150)")
        void interExercisedPriorMonth_notReTaxed() {
            // Local client 7 is SELLER of an inter-bank OTC option exercised in a PRIOR month
            // (2020-01) — already taxed in the prior run. Seller also has a tiny CURRENT-month stock
            // SELL (gain 1) so they are processed this run. The prior-month inter OTC must NOT be
            // re-taxed: total profit must be 1 (stock only), NOT 1151.
            //   BEFORE FIX (RED): inter OTC net added unconditionally → totalProfit = 1 + 1150 = 1151.
            //   AFTER FIX (GREEN): exercisedAt(2020-01) ∉ current period → inter OTC contributes 0 → 1.
            Listing aapl = stockListing(50L, "RSD", "AAPL");
            InterbankOtcExercisedDto priorMonth = interExercised(7L, "SELLER", "AAPL",
                    "10", "100", "150", LocalDateTime.of(2020, 1, 15, 10, 0));
            Order tinySell = currentMonthStockSell(7L, aapl, "1", 1); // gain = 1 RSD this period

            when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(tinySell));
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(Collections.emptyList());
            when(bankaCoreClient.getExercisedInterbankOtc()).thenReturn(List.of(priorMonth));
            when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(aapl));
            when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(user(7L, "CLIENT", "Sel", "Ler"));
            when(taxRecordRepository.findByUserIdAndUserType(7L, "CLIENT")).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(bankaCoreClient.collectTax(any(), any()))
                    .thenAnswer(inv -> new TaxCollectResponse(7L,
                            ((TaxCollectRequest) inv.getArgument(1)).amount(), true));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository).save(captor.capture());
            TaxRecord seller = captor.getValue();
            assertThat(seller.getUserId()).isEqualTo(7L);
            assertThat(seller.getTotalProfit()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(seller.getTaxOwed()).isEqualByComparingTo(new BigDecimal("0.1500"));
            // CRITICAL: the 172.50 inter-OTC tax is NOT re-collected; only the tiny stock tax (0.15).
            ArgumentCaptor<TaxCollectRequest> reqCap = ArgumentCaptor.forClass(TaxCollectRequest.class);
            verify(bankaCoreClient, times(1)).collectTax(any(), reqCap.capture());
            assertThat(reqCap.getValue().amount()).isEqualByComparingTo("0.1500");
        }

        @Test
        @DisplayName("INTER first-run unchanged: inter-bank contract exercised in the CURRENT month is taxed once")
        void interExercisedCurrentMonth_taxedOnce() {
            Listing aapl = stockListing(50L, "RSD", "AAPL");
            InterbankOtcExercisedDto currentMonth = interExercised(7L, "SELLER", "AAPL",
                    "10", "100", "150", LocalDateTime.now());

            when(orderRepository.findByIsDoneTrue()).thenReturn(Collections.emptyList());
            when(otcContractRepository.findExercisedStockContracts()).thenReturn(Collections.emptyList());
            when(bankaCoreClient.getExercisedInterbankOtc()).thenReturn(List.of(currentMonth));
            when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(aapl));
            when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(user(7L, "CLIENT", "Sel", "Ler"));
            when(taxRecordRepository.findByUserIdAndUserType(any(), any())).thenReturn(Optional.empty());
            when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(bankaCoreClient.collectTax(any(), any()))
                    .thenAnswer(inv -> new TaxCollectResponse(7L,
                            ((TaxCollectRequest) inv.getArgument(1)).amount(), true));

            taxService.calculateTaxForAllUsers();

            ArgumentCaptor<TaxRecord> captor = ArgumentCaptor.forClass(TaxRecord.class);
            verify(taxRecordRepository).save(captor.capture());
            TaxRecord seller = captor.getValue();
            assertThat(seller.getUserId()).isEqualTo(7L);
            assertThat(seller.getTotalProfit()).isEqualByComparingTo(new BigDecimal("1150"));
            assertThat(seller.getTaxOwed()).isEqualByComparingTo(new BigDecimal("172.5000"));
            verify(bankaCoreClient, times(1)).collectTax(any(), any());
        }
    }

    // ─── getTaxRecords ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getTaxRecords")
    class GetTaxRecords {

        @Test
        @DisplayName("returns filtered records")
        void filteredRecords() {
            TaxRecord record = TaxRecord.builder()
                    .id(1L).userId(1L).userName("Marko P").userType("CLIENT")
                    .totalProfit(new BigDecimal("500")).taxOwed(new BigDecimal("75"))
                    .taxPaid(BigDecimal.ZERO).currency("RSD").build();

            when(taxRecordRepository.findByFilters("Marko", "CLIENT")).thenReturn(List.of(record));

            List<TaxRecordDto> result = taxService.getTaxRecords("Marko", "CLIENT");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUserName()).isEqualTo("Marko P");
        }

        @Test
        @DisplayName("returns empty list when no records match")
        void noRecords() {
            when(taxRecordRepository.findByFilters(any(), any())).thenReturn(Collections.emptyList());

            List<TaxRecordDto> result = taxService.getTaxRecords("NonExistent", null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("null and blank filters are converted to null for query")
        void nullAndBlankFilters() {
            when(taxRecordRepository.findByFilters(null, null)).thenReturn(Collections.emptyList());

            taxService.getTaxRecords("", "  ");

            verify(taxRecordRepository).findByFilters(null, null);
        }
    }

    // ─── getMyTaxRecord ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMyTaxRecord")
    class GetMyTaxRecord {

        @Test
        @DisplayName("returns existing employee tax record")
        void employeeWithRecord() {
            TaxRecord record = TaxRecord.builder()
                    .id(1L).userId(5L).userName("Ana Anic").userType("EMPLOYEE")
                    .totalProfit(new BigDecimal("1000")).taxOwed(new BigDecimal("150"))
                    .taxPaid(BigDecimal.ZERO).currency("RSD").build();

            when(bankaCoreClient.getUserByEmail("ana@banka.rs"))
                    .thenReturn(user(5L, "EMPLOYEE", "Ana", "Anic"));
            when(taxRecordRepository.findByUserIdAndUserType(5L, "EMPLOYEE")).thenReturn(Optional.of(record));

            TaxRecordDto dto = taxService.getMyTaxRecord("ana@banka.rs");

            assertThat(dto.getUserName()).isEqualTo("Ana Anic");
            assertThat(dto.getTaxOwed()).isEqualByComparingTo(new BigDecimal("150"));
        }

        @Test
        @DisplayName("returns empty DTO when employee has no record")
        void employeeWithoutRecord() {
            when(bankaCoreClient.getUserByEmail("ana@banka.rs"))
                    .thenReturn(user(5L, "EMPLOYEE", "Ana", "Anic"));
            when(taxRecordRepository.findByUserIdAndUserType(5L, "EMPLOYEE")).thenReturn(Optional.empty());

            TaxRecordDto dto = taxService.getMyTaxRecord("ana@banka.rs");

            assertThat(dto.getUserId()).isEqualTo(5L);
            assertThat(dto.getUserType()).isEqualTo("EMPLOYEE");
            assertThat(dto.getTotalProfit()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(dto.getTaxOwed()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("returns existing client tax record")
        void clientWithRecord() {
            TaxRecord record = TaxRecord.builder()
                    .id(2L).userId(10L).userName("Marko Petrovic").userType("CLIENT")
                    .totalProfit(new BigDecimal("2000")).taxOwed(new BigDecimal("300"))
                    .taxPaid(new BigDecimal("50")).currency("RSD").build();

            when(bankaCoreClient.getUserByEmail("marko@test.com"))
                    .thenReturn(user(10L, "CLIENT", "Marko", "Petrovic"));
            when(taxRecordRepository.findByUserIdAndUserType(10L, "CLIENT")).thenReturn(Optional.of(record));

            TaxRecordDto dto = taxService.getMyTaxRecord("marko@test.com");

            assertThat(dto.getUserName()).isEqualTo("Marko Petrovic");
            assertThat(dto.getTaxPaid()).isEqualByComparingTo(new BigDecimal("50"));
        }

        @Test
        @DisplayName("returns empty DTO when banka-core cannot resolve the email")
        void unknownEmail() {
            // NAPOMENA (faza 2c): monolit je vracao prazan DTO kad email ne odgovara
            // ni klijentu ni zaposlenom. trading-service razresava identitet preko
            // banka-core; 404/greska → BankaCoreClientException → prazan DTO ("Nepoznat").
            when(bankaCoreClient.getUserByEmail("nobody@test.com"))
                    .thenThrow(new rs.raf.trading.client.BankaCoreClientException(404, "not found"));

            TaxRecordDto dto = taxService.getMyTaxRecord("nobody@test.com");

            assertThat(dto.getUserId()).isEqualTo(0L);
            assertThat(dto.getUserName()).isEqualTo("Nepoznat");
        }
    }
}
