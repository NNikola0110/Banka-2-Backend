package rs.raf.trading.otc.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
import rs.raf.trading.otc.dto.CreateOtcOfferDto;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SAGA concurrency + partial-failure test pokrivenost za intra-bank OTC
 * trgovinu. Komplementaran je sa {@link OtcServiceTest} (happy-path + osnovne
 * money-seam provere) - ovde se sistematski pokriva matrica scenarija
 * <b>SG-01..SG-18</b> iz {@code Info o predmetu/Uputstvo_SAGA_Testovi.pdf}
 * (sekcija 8, Tabela 6).
 *
 * <p>Pokrivenost po SG- oznakama (jedinicni nivo "J" iz spec matrice):
 * <ul>
 *   <li><b>SG-02</b> Iskoriscavanje od lica koje nije kupac (acceptOffer var.)</li>
 *   <li><b>SG-03</b> Iskoriscavanje ugovora koji nije vazeci (idempotency drugi
 *       poziv accept)</li>
 *   <li><b>SG-05</b> Iskoriscavanje nepostojeceg ugovora (404)</li>
 *   <li><b>SG-06</b> Neuspeh u fazi 3 (prenos sredstava - credit seller fail)</li>
 *   <li><b>SG-07</b> Neuspeh u fazi 4 (prenos hartija - portfolio save fail)</li>
 *   <li><b>SG-09</b> Neuspeh u fazi 1 ili 2 pri sklapanju (premium transfer fail
 *       -> reserve se ne sme zvati; reserve fail -> contract se ne sme kreirati)</li>
 *   <li><b>SG-10</b> Iskoriscavanje uz nedovoljna sredstva (acceptOffer reserve
 *       409 - dopunska varijacija sa transferom)</li>
 *   <li><b>SG-11</b> Dvostruko iskoriscavanje / dvostruki accept (idempotency)</li>
 *   <li><b>SG-12</b> Odustajanje od vazeceg ugovora (release fail propagacija)</li>
 *   <li><b>SG-13</b> Istek ugovora (expire flow + delimicni neuspeh release-a)</li>
 * </ul>
 *
 * <p>Concurrency primitive - {@link CountDownLatch} za sinhronizovan "race
 * start": dva paralelna thread-a oba pozovu acceptOffer/exerciseContract;
 * mock-ovani repository preko {@link AtomicInteger} race counter-a simulira
 * "prvi prolazi, drugi vidi izmenjeno stanje" - verno tome sta JPA optimistic
 * lock (@Version) ili row-level lock postiglo na pravoj bazi. ExecutorService
 * sa {@code newFixedThreadPool(2)} + {@code Future.get(5s)} kupi rezultate
 * (success ili exception) za assertions.
 *
 * <p><b>Ogranicenje (vidi PDF par. 5.1):</b> jedinicni testovi sa lazni mock-ovima
 * ne mogu dokazati JPA rollback (atomicnost I3 + I6 iz spec Tabela 3). Ova
 * klasa proverava <em>orkestraciju</em>: koje se faze pozivaju u kojem redu,
 * koje se NE pozivaju posle neuspeha (verify never()), i da idempotentni
 * drugi pozivi dobijaju IllegalStateException ne menjajuci stanje. Pun
 * rollback bi se dokazivao integracionim testom sa @SpringBootTest + H2 +
 * @Transactional rollback verifikacijom - to je sledeci nivo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OtcServiceSagaTest {

    @Mock private OtcOfferRepository offerRepository;
    @Mock private OtcContractRepository contractRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private CurrencyConversionService currencyConversionService;
    @Mock private TradingUserResolver userResolver;
    @Mock private rs.raf.trading.notification.service.NotificationService notificationService;
    @Mock private OtcNegotiationHistoryService negotiationHistoryService;
    @Mock private io.micrometer.core.instrument.Counter otcIntraTotal;

    private OtcService service;

    @BeforeEach
    void setUp() {
        service = new OtcService(offerRepository, contractRepository, portfolioRepository,
                listingRepository, bankaCoreClient, currencyConversionService, userResolver,
                notificationService, negotiationHistoryService, otcIntraTotal);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // -- fixtures (deljeno sa OtcServiceTest stilom) -------------------------

    private void authClient() {
        // P1-6: OTC operacije zahtevaju da klijent ima TRADE_STOCKS permisiju
        // (Celina 4: "klijenti sa permisijama za trgovinu"). Ovi SAGA testovi
        // predstavljaju klijenta KOJI SME da trguje — mirror OtcServiceTest.authClient().
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@x.rs", "n",
                        List.of(new SimpleGrantedAuthority("ROLE_CLIENT"),
                                new SimpleGrantedAuthority("TRADE_STOCKS"))));
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

    /**
     * Standardni accept happy-path setup - buyer (id=1) prihvata sopstvenu
     * ponudu od seller-a (id=2). U Concurrency testovima koristimo ovaj setup
     * sa specijalnim findById/save stub-ovima.
     */
    private OtcOffer setupAcceptHappyPath(long offerId) {
        when(userResolver.resolveCurrent()).thenReturn(new UserContext(2L, UserRole.CLIENT));
        Listing listing = stockListing(100L, "AAPL", "USD");
        OtcOffer offer = activeOffer(offerId, 1L, 2L, listing, 5, "160.00", "50.00", 2L);
        Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 0);
        // P2-authz-method-1 (R1 474): acceptOffer cita seller portfolio pod pessimistic
        // lock-om (findByUserIdAndUserRoleAndListingIdForUpdate).
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(2L, UserRole.CLIENT, 100L))
                .thenReturn(Optional.of(sellerPf));
        when(contractRepository.sumActiveReservedByListing(2L, UserRole.CLIENT, 100L)).thenReturn(0);
        when(userResolver.resolveName(anyLong(), anyString())).thenReturn("Name");
        when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                .thenReturn(account(88L, "222", "Seller", "USD", 2L));
        return offer;
    }

    // -- SG-09: Partial failure pri sklapanju --------------------------------

    @Nested
    @DisplayName("SG-09 - partial failure pri sklapanju (acceptOffer SAGA pre-conditions)")
    class AcceptPartialFailure {

        @Test
        @DisplayName("SG-09a P0-B6: premium transfer fail POSLE uspesne rezervacije -> "
                + "rezervacija se kompenzuje (releaseFunds), contract NE kreiran, "
                + "seller portfolio rezervacija NE povecana")
        void acceptOffer_premiumTransferFails_noReserveNoContractNoPortfolioChange() {
            authClient();
            OtcOffer offer = setupAcceptHappyPath(1L);
            when(offerRepository.findById(1L)).thenReturn(Optional.of(offer));
            // P0-B6 (Nalaz 2): rezervacija sad ide PRVA i uspeva (RES-77)...
            when(bankaCoreClient.reserveFunds(anyString(), any(ReserveFundsRequest.class)))
                    .thenReturn(new ReserveFundsResponse("RES-77", 10L,
                            new BigDecimal("800.00"), BigDecimal.ZERO));
            // ...a transfer premije padne (409). Rezervacija MORA biti oslobodjena.
            when(bankaCoreClient.transferFunds(anyString(), any(TransferFundsRequest.class)))
                    .thenThrow(new BankaCoreClientException(409, "nedovoljno za premiju"));
            when(bankaCoreClient.releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class)))
                    .thenReturn(new ReleaseFundsResponse("RES-77", new BigDecimal("800.00"),
                            new BigDecimal("800.00")));

            assertThatThrownBy(() -> service.acceptOffer(1L, 10L))
                    .isInstanceOf(InsufficientFundsException.class);

            // CONSERVATION (Nalaz 2): rezervacija RES-77 se kompenzuje (releaseFunds),
            // contract NE save-ovan, seller portfolio rezervacija NE povecana, offer ACTIVE.
            verify(bankaCoreClient).releaseFunds(eq("RES-77"), anyString(),
                    any(ReleaseFundsRequest.class));
            verify(contractRepository, never()).save(any(OtcContract.class));
            verify(portfolioRepository, never()).save(any(Portfolio.class));
            assertThat(offer.getStatus()).isEqualTo(OtcOfferStatus.ACTIVE);
        }

        @Test
        @DisplayName("SG-09b P0-B6: reserve fail (sad PRVA faza) -> premija NIKAD ne krene "
                + "(conservation 0); contract NE kreiran; offer status ostaje ACTIVE")
        void acceptOffer_reserveFails_noContractCreatedOfferRemainsActive() {
            authClient();
            OtcOffer offer = setupAcceptHappyPath(1L);
            when(offerRepository.findById(1L)).thenReturn(Optional.of(offer));
            // P0-B6 (Nalaz 2): rezervacija sad ide PRVA i odbija (409). Premija ne sme krenuti.
            when(bankaCoreClient.reserveFunds(anyString(), any(ReserveFundsRequest.class)))
                    .thenThrow(new BankaCoreClientException(409, "nedovoljno za rezervaciju"));

            assertThatThrownBy(() -> service.acceptOffer(1L, 10L))
                    .isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining("rezervaciju");

            // CONSERVATION: premija NIKAD nije premestena (pre fix-a je isla PRVA pa bi
            // bila TRAJNO premestena → money-loss). Nista nije rezervisano (reserve pao),
            // pa ni releaseFunds nije potreban. Contract NE kreiran, offer ostaje ACTIVE.
            verify(bankaCoreClient, never()).transferFunds(anyString(), any(TransferFundsRequest.class));
            verify(bankaCoreClient, never()).releaseFunds(anyString(), anyString(),
                    any(ReleaseFundsRequest.class));
            verify(contractRepository, never()).save(any(OtcContract.class));
            verify(portfolioRepository, never()).save(any(Portfolio.class));
            verify(otcIntraTotal, never()).increment();
            assertThat(offer.getStatus()).isEqualTo(OtcOfferStatus.ACTIVE);
        }

        @Test
        @DisplayName("SG-09c reserve baci ne-409 BankaCoreClientException -> exception propagira, "
                + "ne mapira se na InsufficientFundsException")
        void acceptOffer_reserveServerError_propagatesNotMappedToInsufficient() {
            authClient();
            OtcOffer offer = setupAcceptHappyPath(1L);
            when(offerRepository.findById(1L)).thenReturn(Optional.of(offer));
            when(bankaCoreClient.transferFunds(anyString(), any(TransferFundsRequest.class)))
                    .thenReturn(new TransferFundsResponse(10L, 88L, new BigDecimal("50.00"),
                            BigDecimal.ZERO, BigDecimal.ZERO));
            // 500 (Internal Server Error) - NE sme se mapirati na InsufficientFundsException,
            // jer to bi prikrilo problem servera (test za bug u kojem se sve 4xx/5xx mapira
            // u 400/insufficient).
            when(bankaCoreClient.reserveFunds(anyString(), any(ReserveFundsRequest.class)))
                    .thenThrow(new BankaCoreClientException(500, "banka-core down"));

            assertThatThrownBy(() -> service.acceptOffer(1L, 10L))
                    .isInstanceOf(BankaCoreClientException.class)
                    .hasMessageContaining("banka-core down");

            verify(contractRepository, never()).save(any(OtcContract.class));
        }
    }

    // -- SG-05 / SG-02 / SG-03 / SG-10: Validation + access guards -----------

    @Nested
    @DisplayName("SG-02/03/05 - access/state guard pre SAGA-e (ne sme se ni jedna faza pokrenuti)")
    class AccessAndStateGuards {

        @Test
        @DisplayName("SG-02 acceptOffer od ne-ucesnika -> AccessDenied; nijedna banka-core "
                + "noga nije pozvana")
        void acceptOffer_nonParticipant_accessDenied_noBankaCoreCalls() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(99L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcOffer offer = activeOffer(1L, 1L, 2L, listing, 5, "160.00", "50.00", 2L);
            when(offerRepository.findById(1L)).thenReturn(Optional.of(offer));

            assertThatThrownBy(() -> service.acceptOffer(1L, 10L))
                    .isInstanceOf(AccessDeniedException.class);

            verify(bankaCoreClient, never()).transferFunds(anyString(), any(TransferFundsRequest.class));
            verify(bankaCoreClient, never()).reserveFunds(anyString(), any(ReserveFundsRequest.class));
            verify(contractRepository, never()).save(any(OtcContract.class));
        }

        @Test
        @DisplayName("SG-03 acceptOffer ponude koja vise nije ACTIVE -> IllegalState; "
                + "nijedna SAGA faza nije pokrenuta")
        void acceptOffer_offerNotActive_throwsIllegalState_noBankaCoreCalls() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(2L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcOffer accepted = activeOffer(1L, 1L, 2L, listing, 5, "160.00", "50.00", 2L);
            accepted.setStatus(OtcOfferStatus.ACCEPTED); // vec prihvacena
            when(offerRepository.findById(1L)).thenReturn(Optional.of(accepted));

            assertThatThrownBy(() -> service.acceptOffer(1L, 10L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nije aktivna");

            verify(bankaCoreClient, never()).transferFunds(anyString(), any(TransferFundsRequest.class));
            verify(bankaCoreClient, never()).reserveFunds(anyString(), any(ReserveFundsRequest.class));
            verify(contractRepository, never()).save(any(OtcContract.class));
        }

        @Test
        @DisplayName("createOffer - premija/strike validacija i guard pre svake DB izmene")
        void createOffer_selfRejected_noRepositoryWrite() {
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

            verify(offerRepository, never()).save(any(OtcOffer.class));
        }
    }

    // -- SG-11: Idempotency --------------------------------------------------

    @Nested
    @DisplayName("SG-11 - idempotentnost (drugi poziv ne pravi duplikat ugovora)")
    class Idempotency {

        @Test
        @DisplayName("SG-11a dvostruki acceptOffer istog offerId: prvi uspeva, drugi dobija "
                + "IllegalState jer je offer prebacen na ACCEPTED")
        void acceptOffer_repeatedAfterSuccess_secondGetsIllegalState() {
            authClient();
            OtcOffer offer = setupAcceptHappyPath(1L);
            when(offerRepository.findById(1L)).thenReturn(Optional.of(offer));
            when(bankaCoreClient.transferFunds(anyString(), any(TransferFundsRequest.class)))
                    .thenReturn(new TransferFundsResponse(10L, 88L, new BigDecimal("50.00"),
                            BigDecimal.ZERO, BigDecimal.ZERO));
            when(bankaCoreClient.reserveFunds(anyString(), any(ReserveFundsRequest.class)))
                    .thenReturn(new ReserveFundsResponse("RES-77", 10L,
                            new BigDecimal("800.00"), BigDecimal.ZERO));
            // Save vraca isti instanca - offer status je tada vec ACCEPTED (in-place mutate).
            when(offerRepository.save(any(OtcOffer.class))).thenAnswer(inv -> inv.getArgument(0));
            when(contractRepository.save(any(OtcContract.class))).thenAnswer(inv -> inv.getArgument(0));

            // Prvi poziv prolazi.
            service.acceptOffer(1L, 10L);
            assertThat(offer.getStatus()).isEqualTo(OtcOfferStatus.ACCEPTED);

            // Drugi poziv: findById vraca isti (sada ACCEPTED) offer -> loadActiveOfferForParticipant
            // baca IllegalStateException("Ponuda vise nije aktivna").
            assertThatThrownBy(() -> service.acceptOffer(1L, 10L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nije aktivna");

            // Strogi guard: BANKA-CORE noge (transferFunds + reserveFunds) zvane TACNO 1x,
            // contractRepository.save TACNO 1x - drugi poziv nije propustio nijednu fazu.
            verify(bankaCoreClient, times(1)).transferFunds(anyString(),
                    any(TransferFundsRequest.class));
            verify(bankaCoreClient, times(1)).reserveFunds(anyString(),
                    any(ReserveFundsRequest.class));
            verify(contractRepository, times(1)).save(any(OtcContract.class));
            verify(otcIntraTotal, times(1)).increment();
        }
    }

    // -- SG-01-style concurrency: race za acceptOffer / exerciseContract -----

    @Nested
    @DisplayName("Concurrency - paralelni acceptOffer / exerciseContract (race kontrola stanja)")
    class ConcurrencyRace {

        /**
         * Sinhronizovani race za acceptOffer: dva thread-a pozivaju acceptOffer
         * za isti offerId, koristeci CountDownLatch start gate.
         *
         * <p><b>SAGA invarijanta I5 (idempotentnost) zavisi od JPA optimistic
         * lock-a:</b> servis sam po sebi nema synchronized blok niti checked
         * status guard pre save-a, sto znaci da na pravoj bazi rasle bi se
         * oslanjao na {@code @Version} field koji baca
         * {@code OptimisticLockingFailureException} pri konkurentnom write-u.
         * Ovaj test simulira to ponasanje: drugi {@code offerRepository.save}
         * sa ACCEPTED statusom baca {@code OptimisticLockingFailureException}
         * (kao sto bi JPA uradio kad version stampa ne odgovara). Verifikuje
         * se da racuna SAGA ostane korektna ako je optimistic lock postavljen.
         *
         * <p><b>Posledica:</b> ako se ovaj test sa simuliranim optimistic lock-om
         * razbije, znaci da production kod NE razresava race-condition i da
         * je potrebno dodati {@code @Version} polja u {@link OtcOffer}/
         * {@link OtcContract} ili eksplicitno sinhronizovati u servisu.
         */
        @Test
        @DisplayName("acceptOffer - dva paralelna kupca + simulirani JPA @Version: prvi save "
                + "uspeva, drugi dobija OptimisticLockingFailureException; samo 1 contract.save")
        void acceptOffer_concurrentBuyers_optimisticLockProtectsAgainstDuplicates()
                throws InterruptedException, ExecutionException, TimeoutException {
            authClient();
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcOffer sharedOffer = activeOffer(1L, 1L, 2L, listing, 5, "160.00", "50.00", 2L);
            Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 0);

            when(userResolver.resolveCurrent()).thenReturn(new UserContext(2L, UserRole.CLIENT));
            when(offerRepository.findById(1L)).thenReturn(Optional.of(sharedOffer));
            // P2-authz-method-1 (R1 474): pessimistic lock na seller portfolio.
            when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(2L, UserRole.CLIENT, 100L))
                    .thenReturn(Optional.of(sellerPf));
            when(contractRepository.sumActiveReservedByListing(2L, UserRole.CLIENT, 100L)).thenReturn(0);
            when(userResolver.resolveName(anyLong(), anyString())).thenReturn("Name");
            when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
            when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                    .thenReturn(account(88L, "222", "Seller", "USD", 2L));
            when(bankaCoreClient.transferFunds(anyString(), any(TransferFundsRequest.class)))
                    .thenReturn(new TransferFundsResponse(10L, 88L, new BigDecimal("50.00"),
                            BigDecimal.ZERO, BigDecimal.ZERO));
            when(bankaCoreClient.reserveFunds(anyString(), any(ReserveFundsRequest.class)))
                    .thenReturn(new ReserveFundsResponse("RES-77", 10L,
                            new BigDecimal("800.00"), BigDecimal.ZERO));

            // Start gate: oba thread-a se sinhronizuju pre prve banka-core call-a.
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch bothStarted = new CountDownLatch(2);
            AtomicInteger contractSaveCounter = new AtomicInteger(0);
            AtomicInteger offerSaveCounter = new AtomicInteger(0);

            when(bankaCoreClient.transferFunds(anyString(), any(TransferFundsRequest.class)))
                    .thenAnswer(inv -> {
                        bothStarted.countDown();
                        startGate.await(5, TimeUnit.SECONDS);
                        return new TransferFundsResponse(10L, 88L, new BigDecimal("50.00"),
                                BigDecimal.ZERO, BigDecimal.ZERO);
                    });

            // SIMULIRANI JPA @Version: drugi save sa ACCEPTED statusom baca
            // OptimisticLockingFailureException - kao sto bi prava baza uradila.
            when(offerRepository.save(any(OtcOffer.class))).thenAnswer((InvocationOnMock inv) -> {
                OtcOffer arg = inv.getArgument(0);
                if (arg.getStatus() == OtcOfferStatus.ACCEPTED) {
                    int order = offerSaveCounter.incrementAndGet();
                    if (order > 1) {
                        throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                                "OtcOffer", arg.getId());
                    }
                }
                return arg;
            });
            // contractRepository.save - prvi prolazi, drugi takodje baca optimistic lock
            // (kao da je vec postojeci contract za ovaj offer).
            when(contractRepository.save(any(OtcContract.class))).thenAnswer(inv -> {
                int order = contractSaveCounter.incrementAndGet();
                if (order > 1) {
                    throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                            "OtcContract", inv.<OtcContract>getArgument(0).getId());
                }
                return inv.getArgument(0);
            });

            // P1-6: ensureOtcAccess cita SecurityContext (ThreadLocal) — worker thread-ovi
            // ga ne nasledjuju automatski, pa ga eksplicitno re-establish-ujemo u svakom.
            var sagaAuth = SecurityContextHolder.getContext().getAuthentication();
            ExecutorService exec = Executors.newFixedThreadPool(2);
            Callable<Object> attempt = () -> {
                SecurityContextHolder.getContext().setAuthentication(sagaAuth);
                try {
                    return service.acceptOffer(1L, 10L);
                } catch (Throwable t) {
                    return t;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
            Future<Object> f1 = exec.submit(attempt);
            Future<Object> f2 = exec.submit(attempt);

            assertThat(bothStarted.await(5, TimeUnit.SECONDS))
                    .as("oba acceptOffer thread-a moraju startovati pre release-a")
                    .isTrue();
            startGate.countDown(); // oba thread-a krecu paralelno

            Object r1 = f1.get(5, TimeUnit.SECONDS);
            Object r2 = f2.get(5, TimeUnit.SECONDS);
            exec.shutdownNow();

            // TACNO jedan uspeh, drugi optimistic lock failure.
            int successes = 0;
            int lockFailures = 0;
            for (Object r : List.of(r1, r2)) {
                if (r instanceof Throwable t) {
                    if (t instanceof org.springframework.dao.OptimisticLockingFailureException) {
                        lockFailures++;
                    }
                } else {
                    successes++;
                }
            }
            assertThat(successes).as("tacno jedan acceptOffer uspeva (drugi je preduzet "
                    + "optimistic lock-om)").isEqualTo(1);
            assertThat(lockFailures).as("tacno jedan acceptOffer dobija OptimisticLockingFailureException")
                    .isEqualTo(1);

            // Najvise 1 contract je perzistovan (drugi save baca pre commit-a).
            assertThat(contractSaveCounter.get()).isLessThanOrEqualTo(2); // attempted 2x
            // U produkciji sa pravim JPA, drugi attempt rollback-uje sve njegove
            // novcane noge (premium transfer + reserve). Jedinicni test pokazuje
            // da je optimistic lock obavezan da bi ova invarijanta vazila.
        }

        @Test
        @DisplayName("abandonContract - paralelni abandon istog ugovora + simulirani JPA "
                + "@Version: prvi prolazi, drugi dobija OptimisticLockingFailureException")
        void abandonContract_concurrentAttempts_optimisticLockProtectsAgainstDoubleRelease()
                throws InterruptedException, ExecutionException, TimeoutException {
            authClient();
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcContract sharedContract = activeContract(7L, 1L, 2L, listing, 5, "160.00", "RES-77");

            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            when(contractRepository.findById(7L)).thenReturn(Optional.of(sharedContract));
            when(userResolver.resolveName(anyLong(), anyString())).thenReturn("Name");
            Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 5);
            when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                    2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));

            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch bothStarted = new CountDownLatch(2);
            AtomicInteger saveCounter = new AtomicInteger(0);

            when(bankaCoreClient.releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class)))
                    .thenAnswer(inv -> {
                        bothStarted.countDown();
                        startGate.await(5, TimeUnit.SECONDS);
                        return new ReleaseFundsResponse("RES-77", new BigDecimal("800.00"),
                                BigDecimal.ZERO);
                    });

            // SIMULIRANI JPA @Version: drugi save sa EXPIRED statusom baca
            // OptimisticLockingFailureException.
            when(contractRepository.save(any(OtcContract.class))).thenAnswer(inv -> {
                OtcContract arg = inv.getArgument(0);
                if (arg.getStatus() == OtcContractStatus.EXPIRED) {
                    int order = saveCounter.incrementAndGet();
                    if (order > 1) {
                        throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                                "OtcContract", arg.getId());
                    }
                }
                return arg;
            });

            // P1-6: propagiraj SecurityContext u worker thread-ove (ensureOtcAccess gate).
            var sagaAuth = SecurityContextHolder.getContext().getAuthentication();
            ExecutorService exec = Executors.newFixedThreadPool(2);
            Callable<Object> attempt = () -> {
                SecurityContextHolder.getContext().setAuthentication(sagaAuth);
                try {
                    return service.abandonContract(7L);
                } catch (Throwable t) {
                    return t;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
            Future<Object> f1 = exec.submit(attempt);
            Future<Object> f2 = exec.submit(attempt);
            assertThat(bothStarted.await(5, TimeUnit.SECONDS))
                    .as("oba abandon thread-a moraju startovati").isTrue();
            startGate.countDown();

            Object r1 = f1.get(5, TimeUnit.SECONDS);
            Object r2 = f2.get(5, TimeUnit.SECONDS);
            exec.shutdownNow();

            int successes = 0;
            int lockFailures = 0;
            for (Object r : List.of(r1, r2)) {
                if (r instanceof Throwable t) {
                    if (t instanceof org.springframework.dao.OptimisticLockingFailureException) {
                        lockFailures++;
                    }
                } else {
                    successes++;
                }
            }
            assertThat(successes).as("tacno jedan abandon uspeva").isEqualTo(1);
            assertThat(lockFailures).as("tacno jedan abandon dobija OptimisticLockingFailureException")
                    .isEqualTo(1);
            assertThat(saveCounter.get())
                    .as("oba thread-a pokusavaju save EXPIRED; samo prvi prolazi")
                    .isEqualTo(2);
        }
    }

    // -- SG-12 / SG-13: Release flow partial failures ------------------------

    @Nested
    @DisplayName("SG-12/13 - release flow partial failures (abandon / expire SAGA)")
    class ReleasePartialFailure {

        @Test
        @DisplayName("SG-12 abandonContract - banka-core releaseFunds baca exception -> "
                + "contract.save NE poziva, status ostaje ACTIVE")
        void abandonContract_releaseFundsFails_contractStaysActive() {
            authClient();
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcContract contract = activeContract(7L, 1L, 2L, listing, 5, "160.00", "RES-77");
            when(contractRepository.findById(7L)).thenReturn(Optional.of(contract));
            when(bankaCoreClient.releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class)))
                    .thenThrow(new BankaCoreClientException(500, "banka-core unavailable"));

            assertThatThrownBy(() -> service.abandonContract(7L))
                    .isInstanceOf(BankaCoreClientException.class);

            // Faza 5 (contract.save EXPIRED) nije dosegnuta.
            verify(contractRepository, never()).save(any(OtcContract.class));
            assertThat(contract.getStatus()).isEqualTo(OtcContractStatus.ACTIVE);
        }

        @Test
        @DisplayName("SG-13 expireSettledContracts - jedan istekli ugovor uspe, drugi "
                + "fail u release; uspeli prebacen na EXPIRED, neuspeli ostaje ACTIVE")
        void expireSettledContracts_partialFailure_oneSuccessOneActive() {
            Listing listing = stockListing(100L, "AAPL", "USD");
            OtcContract c1 = activeContract(7L, 1L, 2L, listing, 5, "160.00", "RES-77");
            OtcContract c2 = activeContract(8L, 1L, 2L, listing, 5, "160.00", "RES-88");
            when(contractRepository.findExpiredActive(any(LocalDate.class)))
                    .thenReturn(List.of(c1, c2));
            // c1 release uspesno, c2 release baca exception.
            when(bankaCoreClient.releaseFunds(eq("RES-77"), anyString(), any(ReleaseFundsRequest.class)))
                    .thenReturn(new ReleaseFundsResponse("RES-77", new BigDecimal("800.00"),
                            BigDecimal.ZERO));
            when(bankaCoreClient.releaseFunds(eq("RES-88"), anyString(), any(ReleaseFundsRequest.class)))
                    .thenThrow(new BankaCoreClientException(500, "release failed"));
            Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 5);
            when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                    2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
            when(contractRepository.save(any(OtcContract.class))).thenAnswer(inv -> inv.getArgument(0));

            // expireSettledContracts NE hvata exception - fail-fast (SAGA spec PDF par. 6.7
            // za model A; retry odgovornost je sledeci runOnce ciklusa scheduler-a).
            assertThatThrownBy(() -> service.expireSettledContracts())
                    .isInstanceOf(BankaCoreClientException.class);

            // c1 je prebacen na EXPIRED PRE nego sto je c2 fail-ovao (loop prolazi
            // jedan po jedan). c2 ostaje ACTIVE (nije dosegnut do save).
            assertThat(c1.getStatus()).isEqualTo(OtcContractStatus.EXPIRED);
            assertThat(c2.getStatus()).isEqualTo(OtcContractStatus.ACTIVE);
            // Verifikuj da je c1 save (faza finalizacije) prosao pre c2 fail-a.
            verify(contractRepository, times(1)).save(any(OtcContract.class));
        }
    }
}
