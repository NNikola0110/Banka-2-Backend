package rs.raf.trading.recurringorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.recurringorder.dto.CreateRecurringOrderDto;
import rs.raf.trading.recurringorder.dto.RecurringOrderDto;
import rs.raf.trading.recurringorder.model.RecurringCadence;
import rs.raf.trading.recurringorder.model.RecurringMode;
import rs.raf.trading.recurringorder.model.RecurringOrder;
import rs.raf.trading.recurringorder.repository.RecurringOrderRepository;
import rs.raf.trading.recurringorder.service.RecurringOrderPlacementService;
import rs.raf.trading.recurringorder.service.RecurringOrderService;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit testovi za {@link RecurringOrderService} (mikroservisi varijanta).
 *
 * <p>Order i Listing su lokalni u trading-service-u; racun zivi u banka-core
 * i razresava se kroz {@link BankaCoreClient}. Svi pozivi su mock-ovani —
 * nema @SpringBootTest ni H2 konteksta.
 */
@ExtendWith(MockitoExtension.class)
public class RecurringOrderServiceTest {

    @Mock
    private RecurringOrderRepository recurringOrderRepo;

    @Mock
    private TradingUserResolver userResolver;

    @Mock
    private RecurringOrderPlacementService placementService;

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private BankaCoreClient bankaCoreClient;

    @Mock
    private NotificationService notificationService;

    // Pravi guard (ne mock) — testovi R1-242 zavise od stvarne TRADE_STOCKS logike
    // nad postavljenim SecurityContext-om.
    @org.mockito.Spy
    private rs.raf.trading.security.TradingAccessGuard tradingAccessGuard =
            new rs.raf.trading.security.TradingAccessGuard();

    @InjectMocks
    private RecurringOrderService recurringOrderService;

    private UserContext clientContext;
    private UserContext employeeContext;

    @BeforeEach
    void setUp() {
        clientContext = new UserContext(1L, "CLIENT");
        employeeContext = new UserContext(2L, "EMPLOYEE");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authAs(String role, String... authorities) {
        var auths = new java.util.ArrayList<SimpleGrantedAuthority>();
        auths.add(new SimpleGrantedAuthority("ROLE_" + role));
        for (String a : authorities) {
            auths.add(new SimpleGrantedAuthority(a));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u@b.rs", null, auths));
    }

    private InternalAccountDto clientAccount(Long accountId, Long ownerClientId, BigDecimal available) {
        return new InternalAccountDto(
                accountId, "222000" + accountId, "Owner",
                available, available, BigDecimal.ZERO, "RSD",
                "ACTIVE",
                ownerClientId, null, "PERSONAL");
    }

    private InternalAccountDto employeeAccount(Long accountId, Long ownerEmployeeId, BigDecimal available) {
        return new InternalAccountDto(
                accountId, "222000" + accountId, "Owner",
                available, available, BigDecimal.ZERO, "RSD",
                "ACTIVE",
                null, ownerEmployeeId, "BANK_TRADING");
    }

    @Test
    void create_clientCanCreateRecurringOrder() {
        authAs("CLIENT", "TRADE_STOCKS");
        when(userResolver.resolveCurrent()).thenReturn(clientContext);
        when(bankaCoreClient.getAccount(1L))
                .thenReturn(clientAccount(1L, 1L, new BigDecimal("1000")));

        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        listing.setPrice(new BigDecimal("150"));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));

        RecurringOrder savedOrder = RecurringOrder.builder()
                .id(1L)
                .ownerId(1L)
                .ownerType("CLIENT")
                .listingId(1L)
                .direction("BUY")
                .mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("5"))
                .accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .nextRun(LocalDateTime.now(ZoneOffset.UTC).plusDays(1))
                .active(true)
                .build();
        when(recurringOrderRepo.save(any())).thenReturn(savedOrder);

        CreateRecurringOrderDto dto = new CreateRecurringOrderDto();
        dto.setListingId(1L);
        dto.setDirection("BUY");
        dto.setMode(RecurringMode.BY_QUANTITY);
        dto.setValue(new BigDecimal("5"));
        dto.setAccountId(1L);
        dto.setCadence(RecurringCadence.DAILY);

        RecurringOrderDto result = recurringOrderService.create(dto);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getOwnerId()).isEqualTo(1L);
        assertThat(result.getOwnerType()).isEqualTo("CLIENT");
        verify(recurringOrderRepo).save(any(RecurringOrder.class));
    }

    @Test
    void create_clientBlockedWhenAccountBelongsToSomeoneElse() {
        authAs("CLIENT", "TRADE_STOCKS");
        when(userResolver.resolveCurrent()).thenReturn(clientContext);
        // Account belongs to client 999, not the current client (id 1)
        when(bankaCoreClient.getAccount(1L))
                .thenReturn(clientAccount(1L, 999L, new BigDecimal("1000")));

        CreateRecurringOrderDto dto = new CreateRecurringOrderDto();
        dto.setListingId(1L);
        dto.setDirection("BUY");
        dto.setMode(RecurringMode.BY_QUANTITY);
        dto.setValue(new BigDecimal("5"));
        dto.setAccountId(1L);
        dto.setCadence(RecurringCadence.DAILY);

        assertThatThrownBy(() -> recurringOrderService.create(dto))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void create_employeeBlockedWhenUsingClientAccount() {
        authAs("EMPLOYEE", "SUPERVISOR");
        when(userResolver.resolveCurrent()).thenReturn(employeeContext);
        // Account belongs to a client → employee cannot use it
        when(bankaCoreClient.getAccount(1L))
                .thenReturn(clientAccount(1L, 5L, new BigDecimal("1000")));

        CreateRecurringOrderDto dto = new CreateRecurringOrderDto();
        dto.setListingId(1L);
        dto.setDirection("BUY");
        dto.setMode(RecurringMode.BY_QUANTITY);
        dto.setValue(new BigDecimal("5"));
        dto.setAccountId(1L);
        dto.setCadence(RecurringCadence.DAILY);

        assertThatThrownBy(() -> recurringOrderService.create(dto))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void create_invalidListingThrows() {
        authAs("CLIENT", "TRADE_STOCKS");
        when(userResolver.resolveCurrent()).thenReturn(clientContext);
        when(bankaCoreClient.getAccount(1L))
                .thenReturn(clientAccount(1L, 1L, new BigDecimal("1000")));
        when(listingRepository.findById(999L)).thenReturn(Optional.empty());

        CreateRecurringOrderDto dto = new CreateRecurringOrderDto();
        dto.setListingId(999L);
        dto.setDirection("BUY");
        dto.setMode(RecurringMode.BY_QUANTITY);
        dto.setValue(new BigDecimal("5"));
        dto.setAccountId(1L);
        dto.setCadence(RecurringCadence.DAILY);

        assertThatThrownBy(() -> recurringOrderService.create(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Hartija od vrednosti ne postoji");
    }

    // ---------- [P2-input-validation-1 / R1 527] BY_QUANTITY ceo broj ----------

    @Test
    void create_byQuantityFractionalValue_rejected() {
        authAs("CLIENT", "TRADE_STOCKS");
        when(userResolver.resolveCurrent()).thenReturn(clientContext);

        CreateRecurringOrderDto dto = new CreateRecurringOrderDto();
        dto.setListingId(1L);
        dto.setDirection("BUY");
        dto.setMode(RecurringMode.BY_QUANTITY);
        dto.setValue(new BigDecimal("2.7")); // necelobrojan quantity
        dto.setAccountId(1L);
        dto.setCadence(RecurringCadence.DAILY);

        assertThatThrownBy(() -> recurringOrderService.create(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ceo broj");
        // Validacija pre banka-core poziva → racun se ne dohvata.
        verify(bankaCoreClient, never()).getAccount(anyLong());
    }

    @Test
    void create_byQuantityWholeNumberValue_passesValidation() {
        authAs("CLIENT", "TRADE_STOCKS");
        when(userResolver.resolveCurrent()).thenReturn(clientContext);
        when(bankaCoreClient.getAccount(1L))
                .thenReturn(clientAccount(1L, 1L, new BigDecimal("1000")));
        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        listing.setPrice(new BigDecimal("150"));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));
        when(recurringOrderRepo.save(any())).thenAnswer(inv -> {
            RecurringOrder r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });

        CreateRecurringOrderDto dto = new CreateRecurringOrderDto();
        dto.setListingId(1L);
        dto.setDirection("BUY");
        dto.setMode(RecurringMode.BY_QUANTITY);
        dto.setValue(new BigDecimal("3.00")); // 3.00 je celobrojno (trailing zeros)
        dto.setAccountId(1L);
        dto.setCadence(RecurringCadence.DAILY);

        RecurringOrderDto result = recurringOrderService.create(dto);
        assertThat(result).isNotNull();
    }

    // ---------- R1-242: create primenjuje ensureTradingAccess (TRADE_STOCKS gate) ----------

    @Test
    void create_clientWithoutTradeStocks_blocked() {
        // R1-242: klijent bez TRADE_STOCKS NE sme da kreira trajni nalog (koji bi
        // svaki put padao na placement-time AccessDenied) — fail-fast pri kreiranju.
        authAs("CLIENT"); // bez TRADE_STOCKS
        when(userResolver.resolveCurrent()).thenReturn(clientContext);

        CreateRecurringOrderDto dto = new CreateRecurringOrderDto();
        dto.setListingId(1L);
        dto.setDirection("BUY");
        dto.setMode(RecurringMode.BY_QUANTITY);
        dto.setValue(new BigDecimal("5"));
        dto.setAccountId(1L);
        dto.setCadence(RecurringCadence.DAILY);

        assertThatThrownBy(() -> recurringOrderService.create(dto))
                .isInstanceOf(AccessDeniedException.class);

        // Gate je pre svake banka-core/listing interakcije.
        verify(bankaCoreClient, never()).getAccount(anyLong());
        verify(recurringOrderRepo, never()).save(any());
    }

    @Test
    void create_employeeWithoutTradingAuthority_blocked() {
        // R1-242: zaposleni bez SUPERVISOR/ADMIN/AGENT autoriteta ne moze da trguje.
        authAs("EMPLOYEE"); // obican operater, bez trading autoriteta
        when(userResolver.resolveCurrent()).thenReturn(employeeContext);

        CreateRecurringOrderDto dto = new CreateRecurringOrderDto();
        dto.setListingId(1L);
        dto.setDirection("BUY");
        dto.setMode(RecurringMode.BY_QUANTITY);
        dto.setValue(new BigDecimal("5"));
        dto.setAccountId(1L);
        dto.setCadence(RecurringCadence.DAILY);

        assertThatThrownBy(() -> recurringOrderService.create(dto))
                .isInstanceOf(AccessDeniedException.class);
        verify(recurringOrderRepo, never()).save(any());
    }

    @Test
    void listMy_returnsOnlyOwnersOrders() {
        when(userResolver.resolveCurrent()).thenReturn(clientContext);

        RecurringOrder order1 = RecurringOrder.builder()
                .id(1L).ownerId(1L).ownerType("CLIENT").listingId(1L)
                .direction("BUY").mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("5")).accountId(1L)
                .cadence(RecurringCadence.DAILY).active(true).build();
        RecurringOrder order2 = RecurringOrder.builder()
                .id(2L).ownerId(1L).ownerType("CLIENT").listingId(2L)
                .direction("BUY").mode(RecurringMode.BY_AMOUNT)
                .value(new BigDecimal("100")).accountId(1L)
                .cadence(RecurringCadence.WEEKLY).active(false).build();

        when(recurringOrderRepo.findByOwnerIdAndOwnerTypeOrderByCreatedAtDesc(1L, "CLIENT"))
                .thenReturn(List.of(order1, order2));

        // R1 818: listMy batch-fetch-uje listinge jednim findAllById (ne per-order findById).
        Listing listing1 = new Listing();
        listing1.setId(1L);
        listing1.setTicker("AAPL");
        Listing listing2 = new Listing();
        listing2.setId(2L);
        listing2.setTicker("MSFT");
        when(listingRepository.findAllById(anyIterable())).thenReturn(List.of(listing1, listing2));

        List<RecurringOrderDto> result = recurringOrderService.listMy();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(RecurringOrderDto::getListingTicker)
                .containsExactlyInAnyOrder("AAPL", "MSFT");
    }

    @Test
    void pause_setsActiveToFalse() {
        RecurringOrder order = RecurringOrder.builder()
                .id(1L)
                .ownerId(1L)
                .ownerType("CLIENT")
                .listingId(1L)
                .direction("BUY")
                .mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("5"))
                .accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .active(true)
                .build();

        when(recurringOrderRepo.findById(1L)).thenReturn(Optional.of(order));
        when(userResolver.resolveCurrent()).thenReturn(clientContext);

        order.setActive(false);
        when(recurringOrderRepo.save(any())).thenReturn(order);

        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));

        RecurringOrderDto result = recurringOrderService.pause(1L);

        assertThat(result.isActive()).isFalse();
        ArgumentCaptor<RecurringOrder> captor = ArgumentCaptor.forClass(RecurringOrder.class);
        verify(recurringOrderRepo).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
    }

    @Test
    void pause_throwsWhenNotOwner() {
        RecurringOrder order = RecurringOrder.builder()
                .id(1L)
                .ownerId(999L)
                .ownerType("CLIENT")
                .build();

        when(recurringOrderRepo.findById(1L)).thenReturn(Optional.of(order));
        when(userResolver.resolveCurrent()).thenReturn(clientContext);

        assertThatThrownBy(() -> recurringOrderService.pause(1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void resume_setsActiveToTrueAndAdvancesNextRun() {
        LocalDateTime past = LocalDateTime.now(ZoneOffset.UTC).minusDays(2);
        RecurringOrder order = RecurringOrder.builder()
                .id(1L)
                .ownerId(1L)
                .ownerType("CLIENT")
                .listingId(1L)
                .direction("BUY")
                .mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("5"))
                .accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .nextRun(past)
                .active(false)
                .build();

        when(recurringOrderRepo.findById(1L)).thenReturn(Optional.of(order));
        when(userResolver.resolveCurrent()).thenReturn(clientContext);
        when(recurringOrderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));

        RecurringOrderDto result = recurringOrderService.resume(1L);

        assertThat(result.isActive()).isTrue();
        assertThat(result.getNextRun()).isAfter(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    }

    // ── [TEST-tr-watchlist-recurring-influx-misc-1 / OT-1189] resume nextRun ==
    //    now + tacno-jedan-cadence (ne ostaje u proslosti, ne preskace vise) ─────

    @Test
    void resume_setsNextRunToNowPlusExactlyOneCadence_OT_1189() {
        // OT-1189: resume racuna nextRun = advanceNextRun(now, cadence). Za DAILY to
        // je ~now+1dan; mora biti STROGO u buducnosti i unutar [now+23h, now+25h]
        // (jedan cadence korak, ne ostatak iz proslosti i ne dupli skok).
        LocalDateTime longAgo = LocalDateTime.now(ZoneOffset.UTC).minusDays(30);
        RecurringOrder order = RecurringOrder.builder()
                .id(1L).ownerId(1L).ownerType("CLIENT").listingId(1L)
                .direction("BUY").mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("5")).accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .nextRun(longAgo) // duboko u proslosti — ne sme da utice na resume nextRun
                .active(false).build();

        when(recurringOrderRepo.findById(1L)).thenReturn(Optional.of(order));
        when(userResolver.resolveCurrent()).thenReturn(clientContext);
        when(recurringOrderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));

        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        RecurringOrderDto result = recurringOrderService.resume(1L);
        LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);

        assertThat(result.isActive()).isTrue();
        // now + ~1 dan (DAILY), racunato od trenutka resume-a — NE od starog longAgo.
        assertThat(result.getNextRun()).isAfter(before.plusDays(1).minusMinutes(1));
        assertThat(result.getNextRun()).isBefore(after.plusDays(1).plusMinutes(1));
        // Ne sme da ostane u proslosti (stari nextRun je bio -30 dana).
        assertThat(result.getNextRun()).isAfter(after);
    }

    @Test
    void resume_sameIdDifferentOwnerType_denied_R1_523() {
        // OT-1187 / R1 523: resume isto ide kroz ensureOwner — CLIENT #1 ne sme da
        // reaktivira EMPLOYEE #1 trajni nalog (isti id, razlicit ownerType).
        RecurringOrder employeeOrder = RecurringOrder.builder()
                .id(1L).ownerId(1L).ownerType("EMPLOYEE")
                .cadence(RecurringCadence.DAILY).active(false).build();

        when(recurringOrderRepo.findById(1L)).thenReturn(Optional.of(employeeOrder));
        when(userResolver.resolveCurrent()).thenReturn(clientContext); // CLIENT/1L

        assertThatThrownBy(() -> recurringOrderService.resume(1L))
                .isInstanceOf(AccessDeniedException.class);
        verify(recurringOrderRepo, never()).save(any());
    }

    @Test
    void cancel_deletesOrder() {
        RecurringOrder order = RecurringOrder.builder()
                .id(1L)
                .ownerId(1L)
                .ownerType("CLIENT")
                .build();

        when(recurringOrderRepo.findById(1L)).thenReturn(Optional.of(order));
        when(userResolver.resolveCurrent()).thenReturn(clientContext);

        recurringOrderService.cancel(1L);

        verify(recurringOrderRepo).deleteById(1L);
    }

    @Test
    void getById_sameIdDifferentOwnerType_denied_R1_523() {
        // R1 523: CLIENT #1 NE SME da pristupi EMPLOYEE #1 trajnom nalogu
        // (isti id, razlicit ownerType — razliciti namespace-ovi).
        RecurringOrder employeeOrder = RecurringOrder.builder()
                .id(1L)
                .ownerId(1L)            // isti numericki id kao clientContext
                .ownerType("EMPLOYEE")  // ali drugi namespace
                .listingId(1L)
                .direction("BUY")
                .mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("5"))
                .accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .active(true)
                .build();

        when(recurringOrderRepo.findById(1L)).thenReturn(Optional.of(employeeOrder));
        when(userResolver.resolveCurrent()).thenReturn(clientContext); // CLIENT/1L

        assertThatThrownBy(() -> recurringOrderService.getById(1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void cancel_sameIdDifferentOwnerType_denied_R1_523() {
        RecurringOrder employeeOrder = RecurringOrder.builder()
                .id(1L).ownerId(1L).ownerType("EMPLOYEE").build();

        when(recurringOrderRepo.findById(1L)).thenReturn(Optional.of(employeeOrder));
        when(userResolver.resolveCurrent()).thenReturn(clientContext); // CLIENT/1L

        assertThatThrownBy(() -> recurringOrderService.cancel(1L))
                .isInstanceOf(AccessDeniedException.class);
        verify(recurringOrderRepo, never()).deleteById(anyLong());
    }

    @Test
    void executeOne_byQuantity_createsMarketOrder() {
        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        listing.setPrice(new BigDecimal("150"));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));

        when(bankaCoreClient.getAccount(1L))
                .thenReturn(clientAccount(1L, 1L, new BigDecimal("1000")));

        RecurringOrder order = RecurringOrder.builder()
                .id(1L)
                .ownerId(1L)
                .ownerType("CLIENT")
                .listingId(1L)
                .direction("BUY")
                .mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("5"))
                .accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .nextRun(LocalDateTime.now(ZoneOffset.UTC))
                .active(true)
                .build();

        when(recurringOrderRepo.save(any())).thenReturn(order);

        recurringOrderService.executeOne(order);

        // N1: order-placement je delegiran na placementService (sopstvena REQUIRES_NEW tx
        // + sistemski SecurityContext vlasnika). Quantity = value za BY_QUANTITY.
        verify(placementService).placeMarketOrder(eq(order), eq(5L));
    }

    @Test
    void executeOne_byAmount_calculatesQuantityFromPrice() {
        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        listing.setPrice(new BigDecimal("200"));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));

        when(bankaCoreClient.getAccount(1L))
                .thenReturn(clientAccount(1L, 1L, new BigDecimal("2000")));

        RecurringOrder order = RecurringOrder.builder()
                .id(1L)
                .ownerId(1L)
                .ownerType("CLIENT")
                .listingId(1L)
                .direction("BUY")
                .mode(RecurringMode.BY_AMOUNT)
                .value(new BigDecimal("1000"))
                .accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .nextRun(LocalDateTime.now(ZoneOffset.UTC))
                .active(true)
                .build();

        when(recurringOrderRepo.save(any())).thenReturn(order);

        recurringOrderService.executeOne(order);

        // BY_AMOUNT: quantity = floor(1000/200) = 5.
        verify(placementService).placeMarketOrder(eq(order), eq(5L));
    }

    @Test
    void executeOne_insufficientFunds_skipsAndAdvancesNextRun() {
        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        listing.setPrice(new BigDecimal("150"));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));

        when(bankaCoreClient.getAccount(1L))
                .thenReturn(clientAccount(1L, 1L, BigDecimal.ZERO));

        RecurringOrder order = RecurringOrder.builder()
                .id(1L)
                .ownerId(1L)
                .ownerType("CLIENT")
                .listingId(1L)
                .direction("BUY")
                .mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("5"))
                .accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .nextRun(LocalDateTime.now(ZoneOffset.UTC))
                .active(true)
                .build();

        when(recurringOrderRepo.save(any())).thenReturn(order);

        recurringOrderService.executeOne(order);

        verify(placementService, never()).placeMarketOrder(any(), anyLong());
        verify(recurringOrderRepo).save(any());
    }

    @Test
    void executeOne_quantityLessThanOne_skips() {
        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        listing.setPrice(new BigDecimal("200"));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));

        RecurringOrder order = RecurringOrder.builder()
                .id(1L)
                .ownerId(1L)
                .ownerType("CLIENT")
                .listingId(1L)
                .direction("BUY")
                .mode(RecurringMode.BY_AMOUNT)
                .value(new BigDecimal("0.5")) // floor(0.5/200) = 0
                .accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .nextRun(LocalDateTime.now(ZoneOffset.UTC))
                .active(true)
                .build();

        when(recurringOrderRepo.save(any())).thenReturn(order);

        recurringOrderService.executeOne(order);

        verify(placementService, never()).placeMarketOrder(any(), anyLong());
        verify(recurringOrderRepo).save(any());
    }

    // ---------- R1-240: SELL recurring ne sme da padne na kes-balans pre-check ----------

    @Test
    void executeOne_sell_doesNotApplyCashBalancePreCheck() {
        // R1-240: prodaja ne trosi kes — balance pre-check je smeo da preskoci SELL
        // recurring nalog cak i kad je dostupan kes manji od (cena × kolicina).
        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        listing.setPrice(new BigDecimal("150"));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));

        RecurringOrder order = RecurringOrder.builder()
                .id(1L)
                .ownerId(1L)
                .ownerType("CLIENT")
                .listingId(1L)
                .direction("SELL")
                .mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("5"))
                .accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .nextRun(LocalDateTime.now(ZoneOffset.UTC))
                .active(true)
                .build();

        when(recurringOrderRepo.save(any())).thenReturn(order);

        recurringOrderService.executeOne(order);

        // SELL: placement MORA biti pozvan; balance lookup NE sme da blokira.
        verify(placementService).placeMarketOrder(eq(order), eq(5L));
        // Za SELL ni ne pitamo banka-core za balance.
        verify(bankaCoreClient, never()).getAccount(anyLong());
    }

    @Test
    void executeOne_buy_stillAppliesCashBalancePreCheck() {
        // Regresija: BUY i dalje proverava balance i preskace kad nema kesa.
        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        listing.setPrice(new BigDecimal("150"));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));
        when(bankaCoreClient.getAccount(1L))
                .thenReturn(clientAccount(1L, 1L, BigDecimal.ZERO));

        RecurringOrder order = RecurringOrder.builder()
                .id(1L).ownerId(1L).ownerType("CLIENT").listingId(1L)
                .direction("BUY").mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("5")).accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .nextRun(LocalDateTime.now(ZoneOffset.UTC)).active(true).build();
        when(recurringOrderRepo.save(any())).thenReturn(order);

        recurringOrderService.executeOne(order);

        verify(placementService, never()).placeMarketOrder(any(), anyLong());
    }

    // ---------- R2-1383: catch-up burst — nextRun preskace na buducnost, ne advance po jedan ----------

    @Test
    void executeOne_overdueOrder_advancesNextRunPastNowNotOneCadence() {
        // R2-1383: nakon downtime-a nextRun je vise ciklusa u proslosti. Posle JEDNOG
        // izvrsenja nextRun mora preskociti SVE propustene cikluse i pasti u buducnost
        // (jedan buy po dospelosti, ne burst dok ne sustigne now).
        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        listing.setPrice(new BigDecimal("150"));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));
        when(bankaCoreClient.getAccount(1L))
                .thenReturn(clientAccount(1L, 1L, new BigDecimal("100000")));

        LocalDateTime longAgo = LocalDateTime.now(ZoneOffset.UTC).minusDays(10);
        RecurringOrder order = RecurringOrder.builder()
                .id(1L).ownerId(1L).ownerType("CLIENT").listingId(1L)
                .direction("BUY").mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("5")).accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .nextRun(longAgo).active(true).build();
        when(recurringOrderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        recurringOrderService.executeOne(order);

        ArgumentCaptor<RecurringOrder> captor = ArgumentCaptor.forClass(RecurringOrder.class);
        verify(recurringOrderRepo).save(captor.capture());
        LocalDateTime savedNextRun = captor.getValue().getNextRun();
        // Mora biti u buducnosti (preskocen ceo zaostatak), NE samo longAgo+1 dan.
        assertThat(savedNextRun).isAfter(LocalDateTime.now(ZoneOffset.UTC));
        assertThat(savedNextRun).isAfter(longAgo.plusDays(2));
        // Tacno jedan placement (jedan buy po ciklusu, ne burst).
        verify(placementService, times(1)).placeMarketOrder(eq(order), eq(5L));
    }

    @Test
    void executeOne_onTimeOrder_advancesByExactlyOneCadence() {
        // Regresija: nalog koji je tek dospeo (nextRun ~ now) napreduje za jedan cadence.
        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        listing.setPrice(new BigDecimal("150"));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));
        when(bankaCoreClient.getAccount(1L))
                .thenReturn(clientAccount(1L, 1L, new BigDecimal("100000")));

        LocalDateTime justNow = LocalDateTime.now(ZoneOffset.UTC);
        RecurringOrder order = RecurringOrder.builder()
                .id(1L).ownerId(1L).ownerType("CLIENT").listingId(1L)
                .direction("BUY").mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("5")).accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .nextRun(justNow).active(true).build();
        when(recurringOrderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        recurringOrderService.executeOne(order);

        ArgumentCaptor<RecurringOrder> captor = ArgumentCaptor.forClass(RecurringOrder.class);
        verify(recurringOrderRepo).save(captor.capture());
        LocalDateTime savedNextRun = captor.getValue().getNextRun();
        assertThat(savedNextRun).isAfter(justNow.plusHours(23));
        assertThat(savedNextRun).isBefore(justNow.plusDays(2));
    }

    // ---------- R1-241/R3-1582: infra-fail NE napreduje nextRun (retry), business-fail napreduje ----------

    @Test
    void executeOne_infraFailureDuringPlacement_doesNotAdvanceNextRun() {
        // R1-241: prolazna infra-greska (banka-core/connection) NE sme tiho da pomeri
        // nextRun — nalog mora ostati dospeo i ponoviti se sledeci ciklus.
        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        listing.setPrice(new BigDecimal("150"));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));
        when(bankaCoreClient.getAccount(1L))
                .thenReturn(clientAccount(1L, 1L, new BigDecimal("100000")));

        LocalDateTime nextRun = LocalDateTime.now(ZoneOffset.UTC);
        RecurringOrder order = RecurringOrder.builder()
                .id(1L).ownerId(1L).ownerType("CLIENT").listingId(1L)
                .direction("BUY").mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("5")).accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .nextRun(nextRun).active(true).build();

        // Placement baca infra (BankaCoreClientException) — transient.
        org.mockito.Mockito.doThrow(new rs.raf.trading.client.BankaCoreClientException(503, "connection refused"))
                .when(placementService).placeMarketOrder(eq(order), eq(5L));

        assertThatThrownBy(() -> recurringOrderService.executeOne(order))
                .isInstanceOf(RuntimeException.class);

        // nextRun NE sme da se pomeri (nema save sa pomerenim nextRun).
        verify(recurringOrderRepo, never()).save(any());
    }

    @Test
    void executeOne_businessRejectDuringPlacement_advancesNextRunAndDoesNotThrow() {
        // R1-241: trajna poslovna greska (AccessDenied/limit) napreduje nextRun + ne baca
        // (da scheduler ne busy-loop-uje), uz best-effort notifikaciju.
        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        listing.setPrice(new BigDecimal("150"));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));
        when(bankaCoreClient.getAccount(1L))
                .thenReturn(clientAccount(1L, 1L, new BigDecimal("100000")));

        RecurringOrder order = RecurringOrder.builder()
                .id(1L).ownerId(1L).ownerType("CLIENT").listingId(1L)
                .direction("BUY").mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("5")).accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .nextRun(LocalDateTime.now(ZoneOffset.UTC)).active(true).build();
        when(recurringOrderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        org.mockito.Mockito.doThrow(new AccessDeniedException("nema TRADE_STOCKS"))
                .when(placementService).placeMarketOrder(eq(order), eq(5L));

        recurringOrderService.executeOne(order);

        // nextRun se pomerio (advance), bez propagacije.
        verify(recurringOrderRepo).save(any());
    }

    @Test
    void executeOne_delegatesToPlacementServiceWithComputedQuantity() {
        // N1: executeOne delegira kreiranje ordera na placementService
        // (REQUIRES_NEW + sistemski SecurityContext vlasnika). Ovde proveravamo
        // da se poziva sa pravom kolicinom (value za BY_QUANTITY).
        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        listing.setPrice(new BigDecimal("150"));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));

        when(bankaCoreClient.getAccount(1L))
                .thenReturn(clientAccount(1L, 1L, new BigDecimal("1000")));

        RecurringOrder order = RecurringOrder.builder()
                .id(1L)
                .ownerId(1L)
                .ownerType("CLIENT")
                .listingId(1L)
                .direction("BUY")
                .mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("3"))
                .accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .nextRun(LocalDateTime.now(ZoneOffset.UTC))
                .active(true)
                .build();

        when(recurringOrderRepo.save(any())).thenReturn(order);

        recurringOrderService.executeOne(order);

        verify(placementService).placeMarketOrder(eq(order), eq(3L));
    }
}
