package rs.raf.trading.otc.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.banka2.contracts.internal.CommitFundsRequest;
import rs.raf.banka2.contracts.internal.CommitFundsResponse;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.CreditFundsResponse;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.ReleaseFundsRequest;
import rs.raf.banka2.contracts.internal.ReleaseFundsResponse;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.banka2.contracts.internal.TransferFundsResponse;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.otc.dto.CounterOtcOfferDto;
import rs.raf.trading.otc.dto.CreateOtcOfferDto;
import rs.raf.trading.otc.dto.OtcContractDto;
import rs.raf.trading.otc.dto.OtcListingDto;
import rs.raf.trading.otc.dto.OtcOfferDto;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.otc.model.OtcContractStatus;
import rs.raf.trading.otc.model.OtcOffer;
import rs.raf.trading.otc.model.OtcOfferStatus;
import rs.raf.trading.otc.repository.OtcContractRepository;
import rs.raf.trading.otc.repository.OtcOfferRepository;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit testovi {@link OtcService} — copy-first ekstrakcija + money-seam rewiring
 * (faza 2d-B).
 *
 * <p>NAPOMENA: monolitni {@code OtcService} (rs.raf.banka2_bek.otc) nije imao
 * dedikovane testove (CLAUDE.md "Poznati Problemi" #9). Ovi testovi su novi i
 * verifikuju da je money-seam korektno prevezan: novcane noge (premija,
 * rezervacija, exercise, release) idu kroz {@link BankaCoreClient}, NE kroz
 * direktnu mutaciju racuna. Cista logika (discovery, role guards, validacija
 * settlement-a, rezervacija akcija u {@link Portfolio}) je takodje pokrivena.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OtcServiceTest {

    @Mock private OtcOfferRepository offerRepository;
    @Mock private OtcContractRepository contractRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private CurrencyConversionService currencyConversionService;
    @Mock private TradingUserResolver userResolver;

    private OtcService service;

    @BeforeEach
    void setUp() {
        service = new OtcService(offerRepository, contractRepository, portfolioRepository,
                listingRepository, bankaCoreClient, currencyConversionService, userResolver);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** Postavlja SecurityContext kao CLIENT (OTC dozvoljen klijentima). */
    private void authClient() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@x.rs", "n",
                        List.of(new SimpleGrantedAuthority("ROLE_CLIENT"))));
    }

    /** Postavlja SecurityContext kao supervizor zaposleni (OTC dozvoljen supervizoru). */
    private void authSupervisor() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("sup@x.rs", "n",
                        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"),
                                new SimpleGrantedAuthority("SUPERVISOR"))));
    }

    /** Postavlja SecurityContext kao agent (OTC zabranjen agentu). */
    private void authAgent() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("agent@x.rs", "n",
                        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"),
                                new SimpleGrantedAuthority("AGENT"))));
    }

    private Listing stockListing(long id, String ticker, String ccy) {
        Listing l = new Listing();
        l.setId(id);
        l.setTicker(ticker);
        l.setName(ticker + " Inc.");
        l.setExchangeAcronym("NYSE");
        l.setListingType(ListingType.STOCK);
        l.setPrice(new BigDecimal("150.00"));
        l.setQuoteCurrency(ccy);
        return l;
    }

    private Portfolio sellerPortfolio(long id, long userId, String userRole, long listingId,
                                      int quantity, int publicQty, int reservedQty) {
        Portfolio p = new Portfolio();
        p.setId(id);
        p.setUserId(userId);
        p.setUserRole(userRole);
        p.setListingId(listingId);
        p.setListingTicker("AAPL");
        p.setListingName("AAPL Inc.");
        p.setListingType("STOCK");
        p.setQuantity(quantity);
        p.setAverageBuyPrice(new BigDecimal("100.00"));
        p.setPublicQuantity(publicQty);
        p.setReservedQuantity(reservedQty);
        return p;
    }

    private InternalAccountDto account(long id, String number, String owner, String ccy,
                                       Long ownerClientId) {
        return new InternalAccountDto(id, number, owner,
                new BigDecimal("100000.00"), new BigDecimal("100000.00"), BigDecimal.ZERO,
                ccy, "ACTIVE", ownerClientId, null, "CHECKING");
    }

    private OtcOffer activeOffer(long id, long buyerId, long sellerId, Listing listing,
                                 int qty, String strike, String premium, long waitingOn) {
        OtcOffer o = new OtcOffer();
        o.setId(id);
        o.setBuyerId(buyerId);
        o.setBuyerRole(UserRole.CLIENT);
        o.setSellerId(sellerId);
        o.setSellerRole(UserRole.CLIENT);
        o.setListing(listing);
        o.setQuantity(qty);
        o.setPricePerStock(new BigDecimal(strike));
        o.setPremium(new BigDecimal(premium));
        o.setSettlementDate(LocalDate.now().plusDays(30));
        o.setLastModifiedById(buyerId);
        o.setLastModifiedByName("Buyer");
        o.setWaitingOnUserId(waitingOn);
        o.setStatus(OtcOfferStatus.ACTIVE);
        return o;
    }

    private OtcContract activeContract(long id, long buyerId, long sellerId, Listing listing,
                                       int qty, String strike, String reservationId) {
        OtcContract c = new OtcContract();
        c.setId(id);
        c.setSourceOfferId(1L);
        c.setBuyerId(buyerId);
        c.setBuyerRole(UserRole.CLIENT);
        c.setSellerId(sellerId);
        c.setSellerRole(UserRole.CLIENT);
        c.setListing(listing);
        c.setQuantity(qty);
        c.setStrikePrice(new BigDecimal(strike));
        c.setPremium(new BigDecimal("50.00"));
        c.setSettlementDate(LocalDate.now().plusDays(30));
        c.setStatus(OtcContractStatus.ACTIVE);
        c.setBuyerReservedAccountId(10L);
        c.setBuyerReservedAmount(new BigDecimal(strike).multiply(BigDecimal.valueOf(qty)));
        c.setBankaCoreReservationId(reservationId);
        return c;
    }

    // ── Discovery + access guards ────────────────────────────────────────────

    @Nested
    @DisplayName("Discovery i kontrola pristupa")
    class DiscoveryAndAccess {

        @Test
        @DisplayName("listDiscoveryListings — klijent vidi tude javne akcije klijenata")
        void discovery_clientSeesOtherClientPublicListings() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            Portfolio other = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 0);
            when(portfolioRepository.findAll()).thenReturn(List.of(other));
            when(listingRepository.findById(100L)).thenReturn(Optional.of(listing));
            when(contractRepository.sumActiveReservedByListing(2L, UserRole.CLIENT, 100L)).thenReturn(0);
            when(userResolver.resolveRole(2L)).thenReturn(UserRole.CLIENT);
            when(userResolver.resolveName(2L, UserRole.CLIENT)).thenReturn("Seller Name");

            List<OtcListingDto> result = service.listDiscoveryListings();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getListingTicker()).isEqualTo("AAPL");
            assertThat(result.get(0).getAvailablePublicQuantity()).isEqualTo(10);
        }

        @Test
        @DisplayName("listDiscoveryListings — sopstvene akcije se ne prikazuju")
        void discovery_excludesOwnListings() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Portfolio mine = sellerPortfolio(5L, 1L, UserRole.CLIENT, 100L, 20, 10, 0);
            when(portfolioRepository.findAll()).thenReturn(List.of(mine));

            List<OtcListingDto> result = service.listDiscoveryListings();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("ensureOtcAccess — agent dobija AccessDenied (Celina 4 Nova §145-148)")
        void access_agentDenied() {
            authAgent();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(7L, UserRole.EMPLOYEE));

            assertThatThrownBy(() -> service.listDiscoveryListings())
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("supervizorima i klijentima");
        }

        @Test
        @DisplayName("ensureOtcAccess — supervizor je dozvoljen")
        void access_supervisorAllowed() {
            authSupervisor();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(7L, UserRole.EMPLOYEE));
            when(portfolioRepository.findAll()).thenReturn(List.of());

            assertThat(service.listDiscoveryListings()).isEmpty();
        }

        @Test
        @DisplayName("listMyPublicListings — vraca sopstvene javne akcije")
        void myPublicListings_returnsOwn() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            Portfolio mine = sellerPortfolio(5L, 1L, UserRole.CLIENT, 100L, 20, 10, 0);
            when(portfolioRepository.findByUserIdAndUserRole(1L, UserRole.CLIENT))
                    .thenReturn(List.of(mine));
            when(listingRepository.findById(100L)).thenReturn(Optional.of(listing));
            when(contractRepository.sumActiveReservedByListing(1L, UserRole.CLIENT, 100L)).thenReturn(0);
            when(userResolver.resolveRole(1L)).thenReturn(UserRole.CLIENT);
            when(userResolver.resolveName(1L, UserRole.CLIENT)).thenReturn("Me");

            List<OtcListingDto> result = service.listMyPublicListings();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPublicQuantity()).isEqualTo(10);
        }
    }

    // ── createOffer / counterOffer / declineOffer ────────────────────────────

    @Nested
    @DisplayName("Ponude — kreiranje / kontraponuda / odbijanje")
    class Offers {

        @Test
        @DisplayName("createOffer — kreira ACTIVE ponudu kad prodavac ima dovoljno javnih akcija")
        void createOffer_success() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            when(listingRepository.findById(100L)).thenReturn(Optional.of(listing));
            when(userResolver.resolveRole(2L)).thenReturn(UserRole.CLIENT);
            Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 0);
            when(portfolioRepository.findByUserIdAndUserRole(2L, UserRole.CLIENT))
                    .thenReturn(List.of(sellerPf));
            when(contractRepository.sumActiveReservedByListing(2L, UserRole.CLIENT, 100L)).thenReturn(0);
            when(userResolver.resolveName(1L, UserRole.CLIENT)).thenReturn("Buyer");
            when(userResolver.resolveName(2L, UserRole.CLIENT)).thenReturn("Seller");
            when(offerRepository.save(any(OtcOffer.class))).thenAnswer(inv -> inv.getArgument(0));

            CreateOtcOfferDto dto = new CreateOtcOfferDto();
            dto.setListingId(100L);
            dto.setSellerId(2L);
            dto.setQuantity(5);
            dto.setPricePerStock(new BigDecimal("160.00"));
            dto.setPremium(new BigDecimal("50.00"));
            dto.setSettlementDate(LocalDate.now().plusDays(30));

            OtcOfferDto result = service.createOffer(dto);

            ArgumentCaptor<OtcOffer> captor = ArgumentCaptor.forClass(OtcOffer.class);
            verify(offerRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(OtcOfferStatus.ACTIVE);
            assertThat(captor.getValue().getWaitingOnUserId()).isEqualTo(2L);
            assertThat(captor.getValue().getBuyerId()).isEqualTo(1L);
            assertThat(result.getSellerName()).isEqualTo("Seller");
        }

        @Test
        @DisplayName("createOffer — odbija ponudu sebi samom")
        void createOffer_selfRejected() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            when(listingRepository.findById(100L)).thenReturn(Optional.of(listing));
            when(userResolver.resolveRole(1L)).thenReturn(UserRole.CLIENT);

            CreateOtcOfferDto dto = new CreateOtcOfferDto();
            dto.setListingId(100L);
            dto.setSellerId(1L);
            dto.setQuantity(5);
            dto.setPricePerStock(new BigDecimal("160.00"));
            dto.setPremium(new BigDecimal("50.00"));
            dto.setSettlementDate(LocalDate.now().plusDays(30));

            assertThatThrownBy(() -> service.createOffer(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sami sebi");
        }

        @Test
        @DisplayName("createOffer — odbija ako prodavac nema dovoljno javnih akcija")
        void createOffer_insufficientPublicQty() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            when(listingRepository.findById(100L)).thenReturn(Optional.of(listing));
            when(userResolver.resolveRole(2L)).thenReturn(UserRole.CLIENT);
            Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 3, 0);
            when(portfolioRepository.findByUserIdAndUserRole(2L, UserRole.CLIENT))
                    .thenReturn(List.of(sellerPf));
            when(contractRepository.sumActiveReservedByListing(2L, UserRole.CLIENT, 100L)).thenReturn(0);

            CreateOtcOfferDto dto = new CreateOtcOfferDto();
            dto.setListingId(100L);
            dto.setSellerId(2L);
            dto.setQuantity(10);
            dto.setPricePerStock(new BigDecimal("160.00"));
            dto.setPremium(new BigDecimal("50.00"));
            dto.setSettlementDate(LocalDate.now().plusDays(30));

            assertThatThrownBy(() -> service.createOffer(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("javno nudi 3");
        }

        @Test
        @DisplayName("createOffer — odbija settlement u proslosti")
        void createOffer_pastSettlementRejected() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            when(listingRepository.findById(100L)).thenReturn(Optional.of(listing));

            CreateOtcOfferDto dto = new CreateOtcOfferDto();
            dto.setListingId(100L);
            dto.setSellerId(2L);
            dto.setQuantity(5);
            dto.setPricePerStock(new BigDecimal("160.00"));
            dto.setPremium(new BigDecimal("50.00"));
            dto.setSettlementDate(LocalDate.now().minusDays(1));

            assertThatThrownBy(() -> service.createOffer(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("buducnosti");
        }

        @Test
        @DisplayName("createOffer — odbija ne-STOCK listing")
        void createOffer_nonStockRejected() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Listing futures = stockListing(100L, "CLM26", "USD");
            futures.setListingType(ListingType.FUTURES);
            when(listingRepository.findById(100L)).thenReturn(Optional.of(futures));

            CreateOtcOfferDto dto = new CreateOtcOfferDto();
            dto.setListingId(100L);
            dto.setSellerId(2L);
            dto.setQuantity(5);
            dto.setPricePerStock(new BigDecimal("160.00"));
            dto.setPremium(new BigDecimal("50.00"));
            dto.setSettlementDate(LocalDate.now().plusDays(30));

            assertThatThrownBy(() -> service.createOffer(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("samo za akcije");
        }

        @Test
        @DisplayName("counterOffer — azurira ponudu i prebacuje red na drugu stranu")
        void counterOffer_flipsTurn() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(2L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcOffer offer = activeOffer(1L, 1L, 2L, listing, 5, "160.00", "50.00", 2L);
            when(offerRepository.findById(1L)).thenReturn(Optional.of(offer));
            Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 0);
            when(portfolioRepository.findByUserIdAndUserRole(2L, UserRole.CLIENT))
                    .thenReturn(List.of(sellerPf));
            when(contractRepository.sumActiveReservedByListing(2L, UserRole.CLIENT, 100L)).thenReturn(0);
            when(userResolver.resolveName(anyLong(), anyString())).thenReturn("Name");
            when(offerRepository.save(any(OtcOffer.class))).thenAnswer(inv -> inv.getArgument(0));

            CounterOtcOfferDto dto = new CounterOtcOfferDto();
            dto.setQuantity(4);
            dto.setPricePerStock(new BigDecimal("155.00"));
            dto.setPremium(new BigDecimal("45.00"));
            dto.setSettlementDate(LocalDate.now().plusDays(20));

            service.counterOffer(1L, dto);

            ArgumentCaptor<OtcOffer> captor = ArgumentCaptor.forClass(OtcOffer.class);
            verify(offerRepository).save(captor.capture());
            assertThat(captor.getValue().getQuantity()).isEqualTo(4);
            assertThat(captor.getValue().getWaitingOnUserId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("counterOffer — ne-ucesnik dobija AccessDenied")
        void counterOffer_nonParticipantDenied() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(99L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcOffer offer = activeOffer(1L, 1L, 2L, listing, 5, "160.00", "50.00", 2L);
            when(offerRepository.findById(1L)).thenReturn(Optional.of(offer));

            CounterOtcOfferDto dto = new CounterOtcOfferDto();
            dto.setQuantity(4);
            dto.setPricePerStock(new BigDecimal("155.00"));
            dto.setPremium(new BigDecimal("45.00"));
            dto.setSettlementDate(LocalDate.now().plusDays(20));

            assertThatThrownBy(() -> service.counterOffer(1L, dto))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("declineOffer — postavlja status DECLINED")
        void declineOffer_setsDeclined() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcOffer offer = activeOffer(1L, 1L, 2L, listing, 5, "160.00", "50.00", 2L);
            when(offerRepository.findById(1L)).thenReturn(Optional.of(offer));
            when(userResolver.resolveName(anyLong(), anyString())).thenReturn("Name");
            when(offerRepository.save(any(OtcOffer.class))).thenAnswer(inv -> inv.getArgument(0));

            service.declineOffer(1L);

            ArgumentCaptor<OtcOffer> captor = ArgumentCaptor.forClass(OtcOffer.class);
            verify(offerRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(OtcOfferStatus.DECLINED);
        }
    }

    // ── acceptOffer — money seam: transferFunds + reserveFunds ──────────────

    @Nested
    @DisplayName("acceptOffer — money seam (transferFunds premija + reserveFunds rezervacija)")
    class AcceptOffer {

        @Test
        @DisplayName("acceptOffer — premija ide transferFunds, rezervacija reserveFunds; "
                + "reservationId se cuva na ugovoru")
        void acceptOffer_premiumTransferAndReservation() {
            authClient();
            // seller (id=2) prihvata ponudu kupca (id=1); waitingOn=2
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(2L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcOffer offer = activeOffer(1L, 1L, 2L, listing, 5, "160.00", "50.00", 2L);
            when(offerRepository.findById(1L)).thenReturn(Optional.of(offer));
            Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 0);
            when(portfolioRepository.findByUserIdAndUserRole(2L, UserRole.CLIENT))
                    .thenReturn(List.of(sellerPf));
            when(contractRepository.sumActiveReservedByListing(2L, UserRole.CLIENT, 100L)).thenReturn(0);
            when(userResolver.resolveName(anyLong(), anyString())).thenReturn("Name");

            // buyer account passed in (id=10, USD); seller (klijent #2) → njegov
            // licni preferiran racun (id=88, USD) preko getPreferredAccount.
            InternalAccountDto buyerAcc = account(10L, "111", "Buyer", "USD", 1L);
            InternalAccountDto sellerAcc = account(88L, "222", "Seller", "USD", 2L);
            when(bankaCoreClient.getAccount(10L)).thenReturn(buyerAcc);
            when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                    .thenReturn(sellerAcc);

            when(bankaCoreClient.transferFunds(anyString(), any(TransferFundsRequest.class)))
                    .thenReturn(new TransferFundsResponse(10L, 88L, new BigDecimal("50.00"),
                            BigDecimal.ZERO, BigDecimal.ZERO));
            when(bankaCoreClient.reserveFunds(anyString(), any(ReserveFundsRequest.class)))
                    .thenReturn(new ReserveFundsResponse("RES-77", 10L,
                            new BigDecimal("800.00"), BigDecimal.ZERO));
            when(offerRepository.save(any(OtcOffer.class))).thenAnswer(inv -> inv.getArgument(0));
            when(contractRepository.save(any(OtcContract.class))).thenAnswer(inv -> inv.getArgument(0));

            service.acceptOffer(1L, 10L);

            // premija premium → transferFunds buyer(10) → seller(88), iznos 50
            ArgumentCaptor<String> transferKey = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<TransferFundsRequest> transferReq =
                    ArgumentCaptor.forClass(TransferFundsRequest.class);
            verify(bankaCoreClient).transferFunds(transferKey.capture(), transferReq.capture());
            assertThat(transferKey.getValue()).isEqualTo("otc-accept-1-premium");
            assertThat(transferReq.getValue().fromAccountId()).isEqualTo(10L);
            assertThat(transferReq.getValue().toAccountId()).isEqualTo(88L);
            assertThat(transferReq.getValue().debitAmount()).isEqualByComparingTo("50.00");
            assertThat(transferReq.getValue().creditAmount()).isEqualByComparingTo("50.00");

            // rezervacija strike×qty = 160*5 = 800 → reserveFunds buyer(10)
            ArgumentCaptor<String> reserveKey = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<ReserveFundsRequest> reserveReq =
                    ArgumentCaptor.forClass(ReserveFundsRequest.class);
            verify(bankaCoreClient).reserveFunds(reserveKey.capture(), reserveReq.capture());
            assertThat(reserveKey.getValue()).isEqualTo("otc-accept-1-reserve");
            assertThat(reserveReq.getValue().accountId()).isEqualTo(10L);
            assertThat(reserveReq.getValue().amount()).isEqualByComparingTo("800.00");
            assertThat(reserveReq.getValue().currencyCode()).isEqualTo("USD");

            // ugovor cuva banka-core reservationId + seller portfolio rezervacija raste
            ArgumentCaptor<OtcContract> contractCaptor = ArgumentCaptor.forClass(OtcContract.class);
            verify(contractRepository).save(contractCaptor.capture());
            assertThat(contractCaptor.getValue().getBankaCoreReservationId()).isEqualTo("RES-77");
            assertThat(contractCaptor.getValue().getBuyerReservedAmount()).isEqualByComparingTo("800.00");
            assertThat(contractCaptor.getValue().getStatus()).isEqualTo(OtcContractStatus.ACTIVE);

            ArgumentCaptor<Portfolio> pfCaptor = ArgumentCaptor.forClass(Portfolio.class);
            verify(portfolioRepository).save(pfCaptor.capture());
            assertThat(pfCaptor.getValue().getReservedQuantity()).isEqualTo(5);

            // offer postaje ACCEPTED
            ArgumentCaptor<OtcOffer> offerCaptor = ArgumentCaptor.forClass(OtcOffer.class);
            verify(offerRepository).save(offerCaptor.capture());
            assertThat(offerCaptor.getValue().getStatus()).isEqualTo(OtcOfferStatus.ACCEPTED);
        }

        @Test
        @DisplayName("acceptOffer — seller je ZAPOSLENI → seller racun je bankin "
                + "(getPreferredAccount EMPLOYEE)")
        void acceptOffer_employeeSeller_resolvesBankAccount() {
            authSupervisor();
            // seller (id=2) je ZAPOSLENI; obe strane su zaposleni (ensureSameRoleParticipants).
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(2L, UserRole.EMPLOYEE));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcOffer offer = activeOffer(1L, 1L, 2L, listing, 5, "160.00", "50.00", 2L);
            offer.setBuyerRole(UserRole.EMPLOYEE);
            offer.setSellerRole(UserRole.EMPLOYEE);
            when(offerRepository.findById(1L)).thenReturn(Optional.of(offer));
            Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.EMPLOYEE, 100L, 20, 10, 0);
            when(portfolioRepository.findByUserIdAndUserRole(2L, UserRole.EMPLOYEE))
                    .thenReturn(List.of(sellerPf));
            when(contractRepository.sumActiveReservedByListing(2L, UserRole.EMPLOYEE, 100L))
                    .thenReturn(0);
            when(userResolver.resolveName(anyLong(), anyString())).thenReturn("Name");

            // buyer racun (id=10) prosledjen — i kupac je zaposleni (bankin racun).
            InternalAccountDto buyerAcc = account(10L, "111", "Banka", "USD", null);
            // EMPLOYEE seller → bankin trading racun (id=99, vlasnik nije klijent).
            InternalAccountDto bankAcc = account(99L, "BANK", "Banka", "USD", null);
            when(bankaCoreClient.getAccount(10L)).thenReturn(buyerAcc);
            when(bankaCoreClient.getPreferredAccount(UserRole.EMPLOYEE, 2L, "USD"))
                    .thenReturn(bankAcc);
            when(bankaCoreClient.transferFunds(anyString(), any(TransferFundsRequest.class)))
                    .thenReturn(new TransferFundsResponse(10L, 99L, new BigDecimal("50.00"),
                            BigDecimal.ZERO, BigDecimal.ZERO));
            when(bankaCoreClient.reserveFunds(anyString(), any(ReserveFundsRequest.class)))
                    .thenReturn(new ReserveFundsResponse("RES-77", 10L,
                            new BigDecimal("800.00"), BigDecimal.ZERO));
            when(offerRepository.save(any(OtcOffer.class))).thenAnswer(inv -> inv.getArgument(0));
            when(contractRepository.save(any(OtcContract.class))).thenAnswer(inv -> inv.getArgument(0));

            service.acceptOffer(1L, 10L);

            // premija ide na bankin racun (99) — verno monolitu (EMPLOYEE → bankin racun)
            ArgumentCaptor<TransferFundsRequest> transferReq =
                    ArgumentCaptor.forClass(TransferFundsRequest.class);
            verify(bankaCoreClient).transferFunds(anyString(), transferReq.capture());
            assertThat(transferReq.getValue().toAccountId()).isEqualTo(99L);
            verify(bankaCoreClient).getPreferredAccount(UserRole.EMPLOYEE, 2L, "USD");
        }

        @Test
        @DisplayName("acceptOffer — banka-core 409 na rezervaciji → InsufficientFundsException")
        void acceptOffer_reserveConflictMapsToInsufficientFunds() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(2L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcOffer offer = activeOffer(1L, 1L, 2L, listing, 5, "160.00", "50.00", 2L);
            when(offerRepository.findById(1L)).thenReturn(Optional.of(offer));
            Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 0);
            when(portfolioRepository.findByUserIdAndUserRole(2L, UserRole.CLIENT))
                    .thenReturn(List.of(sellerPf));
            when(contractRepository.sumActiveReservedByListing(2L, UserRole.CLIENT, 100L)).thenReturn(0);
            when(userResolver.resolveName(anyLong(), anyString())).thenReturn("Name");
            when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
            when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                    .thenReturn(account(88L, "222", "Seller", "USD", 2L));
            when(bankaCoreClient.transferFunds(anyString(), any(TransferFundsRequest.class)))
                    .thenReturn(new TransferFundsResponse(10L, 88L, new BigDecimal("50.00"),
                            BigDecimal.ZERO, BigDecimal.ZERO));
            when(bankaCoreClient.reserveFunds(anyString(), any(ReserveFundsRequest.class)))
                    .thenThrow(new BankaCoreClientException(409, "nedovoljno"));

            assertThatThrownBy(() -> service.acceptOffer(1L, 10L))
                    .isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining("rezervaciju");
        }

        @Test
        @DisplayName("acceptOffer — kad nije red korisnika → IllegalStateException")
        void acceptOffer_notMyTurn() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            // waitingOn = 2 (seller), ali korisnik je buyer (1)
            OtcOffer offer = activeOffer(1L, 1L, 2L, listing, 5, "160.00", "50.00", 2L);
            when(offerRepository.findById(1L)).thenReturn(Optional.of(offer));

            assertThatThrownBy(() -> service.acceptOffer(1L, 10L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Nije na vama red");
        }

        @Test
        @DisplayName("acceptOffer — buyerAccountId tudji racun → AccessDenied (verifyAccountOwnership)")
        void acceptOffer_foreignAccountDenied() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(2L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcOffer offer = activeOffer(1L, 1L, 2L, listing, 5, "160.00", "50.00", 2L);
            when(offerRepository.findById(1L)).thenReturn(Optional.of(offer));
            Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 0);
            when(portfolioRepository.findByUserIdAndUserRole(2L, UserRole.CLIENT))
                    .thenReturn(List.of(sellerPf));
            when(contractRepository.sumActiveReservedByListing(2L, UserRole.CLIENT, 100L)).thenReturn(0);
            // buyer is client id=1, but account 10 belongs to ownerClientId=999
            when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "X", "USD", 999L));

            assertThatThrownBy(() -> service.acceptOffer(1L, 10L))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("ne pripada korisniku");
        }
    }

    // ── exerciseContract — money seam: commitFunds + creditFunds ────────────

    @Nested
    @DisplayName("exerciseContract — money seam (commitFunds buyer rezervacija + creditFunds seller)")
    class ExerciseContract {

        @Test
        @DisplayName("exerciseContract — commitFunds buyer rezervacija + creditFunds seller; "
                + "portfolio prebacen")
        void exercise_commitAndCredit() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcContract contract = activeContract(7L, 1L, 2L, listing, 5, "160.00", "RES-77");
            when(contractRepository.findById(7L)).thenReturn(Optional.of(contract));
            when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
            // seller (klijent #2) → njegov licni preferiran racun (id=88, USD)
            when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                    .thenReturn(account(88L, "222", "Seller", "USD", 2L));
            when(bankaCoreClient.commitFunds(anyString(), anyString(), any(CommitFundsRequest.class)))
                    .thenReturn(new CommitFundsResponse("RES-77", new BigDecimal("800.00"),
                            BigDecimal.ZERO, BigDecimal.ZERO));
            when(bankaCoreClient.creditFunds(anyString(), any(CreditFundsRequest.class)))
                    .thenReturn(new CreditFundsResponse(88L, new BigDecimal("800.00"),
                            new BigDecimal("100800.00")));
            Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 5);
            when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                    2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
            when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                    1L, UserRole.CLIENT, 100L)).thenReturn(Optional.empty());
            when(userResolver.resolveName(anyLong(), anyString())).thenReturn("Name");
            when(contractRepository.save(any(OtcContract.class))).thenAnswer(inv -> inv.getArgument(0));

            service.exerciseContract(7L, 10L);

            // 1. commitFunds buyer rezervacija (RES-77, 800, bez beneficiary-ja)
            ArgumentCaptor<String> commitResId = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> commitKey = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<CommitFundsRequest> commitReq =
                    ArgumentCaptor.forClass(CommitFundsRequest.class);
            verify(bankaCoreClient).commitFunds(commitResId.capture(), commitKey.capture(),
                    commitReq.capture());
            assertThat(commitResId.getValue()).isEqualTo("RES-77");
            assertThat(commitKey.getValue()).isEqualTo("otc-exercise-7-commit");
            assertThat(commitReq.getValue().amount()).isEqualByComparingTo("800.00");
            assertThat(commitReq.getValue().beneficiaryAccountId()).isNull();

            // 2. creditFunds seller (njegov licni racun 88, 800)
            ArgumentCaptor<String> creditKey = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<CreditFundsRequest> creditReq =
                    ArgumentCaptor.forClass(CreditFundsRequest.class);
            verify(bankaCoreClient).creditFunds(creditKey.capture(), creditReq.capture());
            assertThat(creditKey.getValue()).isEqualTo("otc-exercise-7-credit");
            assertThat(creditReq.getValue().accountId()).isEqualTo(88L);
            assertThat(creditReq.getValue().amount()).isEqualByComparingTo("800.00");

            // contract postaje EXERCISED
            ArgumentCaptor<OtcContract> contractCaptor = ArgumentCaptor.forClass(OtcContract.class);
            verify(contractRepository).save(contractCaptor.capture());
            assertThat(contractCaptor.getValue().getStatus()).isEqualTo(OtcContractStatus.EXERCISED);
            assertThat(contractCaptor.getValue().getExercisedAt()).isNotNull();
        }

        @Test
        @DisplayName("exerciseContract — legacy ugovor bez rezervacije → fallback transferFunds")
        void exercise_legacyContractUsesTransfer() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcContract contract = activeContract(7L, 1L, 2L, listing, 5, "160.00", null);
            contract.setBuyerReservedAmount(null); // legacy — bez rezervacije
            when(contractRepository.findById(7L)).thenReturn(Optional.of(contract));
            when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
            // seller (klijent #2) → njegov licni preferiran racun (id=88, USD)
            when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                    .thenReturn(account(88L, "222", "Seller", "USD", 2L));
            when(bankaCoreClient.transferFunds(anyString(), any(TransferFundsRequest.class)))
                    .thenReturn(new TransferFundsResponse(10L, 88L, new BigDecimal("800.00"),
                            BigDecimal.ZERO, BigDecimal.ZERO));
            Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 5);
            when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                    2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
            when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                    1L, UserRole.CLIENT, 100L)).thenReturn(Optional.empty());
            when(userResolver.resolveName(anyLong(), anyString())).thenReturn("Name");
            when(contractRepository.save(any(OtcContract.class))).thenAnswer(inv -> inv.getArgument(0));

            service.exerciseContract(7L, 10L);

            // legacy: nema commit/credit, fallback transferFunds
            verify(bankaCoreClient).transferFunds(eq("otc-accept-7-exercise"),
                    any(TransferFundsRequest.class));
            verify(bankaCoreClient, never()).commitFunds(anyString(), anyString(),
                    any(CommitFundsRequest.class));
        }

        @Test
        @DisplayName("exerciseContract — ne-kupac dobija AccessDenied")
        void exercise_nonBuyerDenied() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(2L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcContract contract = activeContract(7L, 1L, 2L, listing, 5, "160.00", "RES-77");
            when(contractRepository.findById(7L)).thenReturn(Optional.of(contract));

            assertThatThrownBy(() -> service.exerciseContract(7L, 10L))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Samo kupac");
        }

        @Test
        @DisplayName("exerciseContract — neaktivan ugovor → IllegalStateException")
        void exercise_inactiveContract() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcContract contract = activeContract(7L, 1L, 2L, listing, 5, "160.00", "RES-77");
            contract.setStatus(OtcContractStatus.EXERCISED);
            when(contractRepository.findById(7L)).thenReturn(Optional.of(contract));

            assertThatThrownBy(() -> service.exerciseContract(7L, 10L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nije aktivan");
        }
    }

    // ── abandonContract / expireSettledContracts — money seam: releaseFunds ─

    @Nested
    @DisplayName("abandon / expire — money seam (releaseFunds)")
    class ReleaseFlows {

        @Test
        @DisplayName("abandonContract — releaseFunds + oslobadja seller portfolio rezervaciju")
        void abandon_releasesReservation() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcContract contract = activeContract(7L, 1L, 2L, listing, 5, "160.00", "RES-77");
            when(contractRepository.findById(7L)).thenReturn(Optional.of(contract));
            when(bankaCoreClient.releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class)))
                    .thenReturn(new ReleaseFundsResponse("RES-77", new BigDecimal("800.00"),
                            BigDecimal.ZERO));
            Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 5);
            when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                    2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
            when(userResolver.resolveName(anyLong(), anyString())).thenReturn("Name");
            when(contractRepository.save(any(OtcContract.class))).thenAnswer(inv -> inv.getArgument(0));

            service.abandonContract(7L);

            ArgumentCaptor<String> resId = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(bankaCoreClient).releaseFunds(resId.capture(), key.capture(),
                    any(ReleaseFundsRequest.class));
            assertThat(resId.getValue()).isEqualTo("RES-77");
            assertThat(key.getValue()).isEqualTo("otc-release-7");

            // seller portfolio rezervacija oslobodjena
            ArgumentCaptor<Portfolio> pfCaptor = ArgumentCaptor.forClass(Portfolio.class);
            verify(portfolioRepository).save(pfCaptor.capture());
            assertThat(pfCaptor.getValue().getReservedQuantity()).isEqualTo(0);

            ArgumentCaptor<OtcContract> contractCaptor = ArgumentCaptor.forClass(OtcContract.class);
            verify(contractRepository).save(contractCaptor.capture());
            assertThat(contractCaptor.getValue().getStatus()).isEqualTo(OtcContractStatus.EXPIRED);
        }

        @Test
        @DisplayName("abandonContract — ne-kupac dobija AccessDenied")
        void abandon_nonBuyerDenied() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(2L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcContract contract = activeContract(7L, 1L, 2L, listing, 5, "160.00", "RES-77");
            when(contractRepository.findById(7L)).thenReturn(Optional.of(contract));

            assertThatThrownBy(() -> service.abandonContract(7L))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Samo kupac");
        }

        @Test
        @DisplayName("expireSettledContracts — releaseFunds za svaki istekli ugovor")
        void expire_releasesEach() {
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcContract c1 = activeContract(7L, 1L, 2L, listing, 5, "160.00", "RES-77");
            when(contractRepository.findExpiredActive(any(LocalDate.class)))
                    .thenReturn(List.of(c1));
            when(bankaCoreClient.releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class)))
                    .thenReturn(new ReleaseFundsResponse("RES-77", new BigDecimal("800.00"),
                            BigDecimal.ZERO));
            Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 5);
            when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                    2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
            when(contractRepository.save(any(OtcContract.class))).thenAnswer(inv -> inv.getArgument(0));

            int count = service.expireSettledContracts();

            assertThat(count).isEqualTo(1);
            verify(bankaCoreClient).releaseFunds(eq("RES-77"), eq("otc-release-7"),
                    any(ReleaseFundsRequest.class));
            assertThat(c1.getStatus()).isEqualTo(OtcContractStatus.EXPIRED);
        }

        @Test
        @DisplayName("expireSettledContracts — legacy ugovor bez reservationId ne zove releaseFunds")
        void expire_legacyContractSkipsRelease() {
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcContract legacy = activeContract(8L, 1L, 2L, listing, 5, "160.00", null);
            legacy.setBuyerReservedAmount(null);
            when(contractRepository.findExpiredActive(any(LocalDate.class)))
                    .thenReturn(List.of(legacy));
            Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 5);
            when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                    2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
            when(contractRepository.save(any(OtcContract.class))).thenAnswer(inv -> inv.getArgument(0));

            service.expireSettledContracts();

            verify(bankaCoreClient, never()).releaseFunds(anyString(), anyString(),
                    any(ReleaseFundsRequest.class));
            assertThat(legacy.getStatus()).isEqualTo(OtcContractStatus.EXPIRED);
        }
    }

    // ── listMyContracts ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("listMyContracts")
    class ListContracts {

        @Test
        @DisplayName("listMyContracts — bez filtera vraca sve ugovore korisnika")
        void listMyContracts_noFilter() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcContract c = activeContract(7L, 1L, 2L, listing, 5, "160.00", "RES-77");
            when(contractRepository.findAllForUser(1L, UserRole.CLIENT)).thenReturn(List.of(c));
            when(userResolver.resolveName(anyLong(), anyString())).thenReturn("Name");

            List<OtcContractDto> result = service.listMyContracts(null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("listMyContracts — nepoznat status filter → IllegalArgumentException")
        void listMyContracts_unknownStatus() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));

            assertThatThrownBy(() -> service.listMyContracts("BOGUS"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Nepoznat status");
        }

        @Test
        @DisplayName("listMyContracts — status filter prosledjuje se repozitorijumu")
        void listMyContracts_statusFilter() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            when(contractRepository.findByUserAndStatus(1L, UserRole.CLIENT, OtcContractStatus.EXERCISED))
                    .thenReturn(List.of());

            service.listMyContracts("exercised");

            verify(contractRepository).findByUserAndStatus(1L, UserRole.CLIENT,
                    OtcContractStatus.EXERCISED);
        }
    }
}
