package rs.raf.trading.watchlist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.stock.dto.ListingDto;
import rs.raf.trading.stock.service.ListingService;
import rs.raf.trading.watchlist.dto.CreateWatchlistDto;
import rs.raf.trading.watchlist.dto.WatchlistItemDto;
import rs.raf.trading.watchlist.dto.WatchlistDto;
import rs.raf.trading.watchlist.model.Watchlist;
import rs.raf.trading.watchlist.model.WatchlistItem;
import rs.raf.trading.watchlist.model.WatchlistOwnerType;
import rs.raf.trading.watchlist.repository.WatchlistItemRepository;
import rs.raf.trading.watchlist.repository.WatchlistRepository;
import rs.raf.trading.watchlist.service.WatchlistService;
import rs.raf.trading.security.TradingUserResolver;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WatchlistServiceTest {

    @InjectMocks
    private WatchlistService watchlistService;

    @Mock private WatchlistRepository watchlistRepo;
    @Mock private WatchlistItemRepository itemRepo;
    @Mock private TradingUserResolver userResolver;
    @Mock private ListingService listingService;

    private final UserContext clientCtx = new UserContext(42L, UserRole.CLIENT);

    @BeforeEach
    void setUp() {
        // nothing
    }

    @Test
    @DisplayName("createWatchlist_success_clientOwner")
    void createWatchlist_success_clientOwner() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        when(watchlistRepo.existsByOwnerIdAndOwnerTypeAndName(42L, WatchlistOwnerType.CLIENT, "Moje akcije"))
                .thenReturn(false);

        Watchlist saved = Watchlist.builder()
                .id(1L)
                .ownerId(42L)
                .ownerType(WatchlistOwnerType.CLIENT)
                .name("Moje akcije")
                .createdAt(LocalDateTime.now())
                .build();
        when(watchlistRepo.save(any(Watchlist.class))).thenReturn(saved);

        CreateWatchlistDto dto = CreateWatchlistDto.builder().name("Moje akcije").build();
        WatchlistDto res = watchlistService.createWatchlist(dto);

        assertEquals(1L, res.getId());
        assertEquals(42L, res.getOwnerId());
        assertEquals("CLIENT", res.getOwnerType());
        assertEquals("Moje akcije", res.getName());
        assertEquals(0, res.getItemCount());
    }

    @Test
    @DisplayName("createWatchlist_throwsIfDuplicateName")
    void createWatchlist_throwsIfDuplicateName() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        when(watchlistRepo.existsByOwnerIdAndOwnerTypeAndName(42L, WatchlistOwnerType.CLIENT, "X"))
                .thenReturn(true);

        CreateWatchlistDto dto = CreateWatchlistDto.builder().name("X").build();
        assertThatThrownBy(() -> watchlistService.createWatchlist(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("X");
    }

    // ── [P2-input-validation-1 / R1 517] DoS limit ──────────────────────────

    @Test
    @DisplayName("createWatchlist_rejectedWhenMaxListsReached")
    void createWatchlist_rejectedWhenMaxListsReached() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        when(watchlistRepo.countByOwnerIdAndOwnerType(42L, WatchlistOwnerType.CLIENT))
                .thenReturn((long) WatchlistService.MAX_WATCHLISTS_PER_USER);

        CreateWatchlistDto dto = CreateWatchlistDto.builder().name("Nova").build();
        assertThatThrownBy(() -> watchlistService.createWatchlist(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maksimalan broj lista");
        verify(watchlistRepo, never()).save(any());
    }

    @Test
    @DisplayName("addItem_rejectedWhenMaxItemsReached")
    void addItem_rejectedWhenMaxItemsReached() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        Watchlist wl = Watchlist.builder().id(7L).ownerId(42L)
                .ownerType(WatchlistOwnerType.CLIENT).name("L").build();
        when(watchlistRepo.findByIdAndOwnerIdAndOwnerType(7L, 42L, WatchlistOwnerType.CLIENT))
                .thenReturn(Optional.of(wl));
        when(itemRepo.existsByWatchlistIdAndListingId(7L, 100L)).thenReturn(false);
        when(itemRepo.countByWatchlistId(7L))
                .thenReturn((long) WatchlistService.MAX_ITEMS_PER_WATCHLIST);

        assertThatThrownBy(() -> watchlistService.addItem(7L, 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maksimalan broj stavki");
        verify(itemRepo, never()).save(any());
    }

    @Test
    @DisplayName("listMyWatchlists_returnsEmptyListWhenNoWatchlists")
    void listMyWatchlists_returnsEmptyListWhenNoWatchlists() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        when(watchlistRepo.findByOwnerIdAndOwnerTypeOrderByCreatedAtAsc(42L, WatchlistOwnerType.CLIENT))
                .thenReturn(Collections.emptyList());

        List<WatchlistDto> res = watchlistService.listMyWatchlists();
        assertThat(res).isEmpty();
    }

    @Test
    @DisplayName("renameWatchlist_success")
    void renameWatchlist_success() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        Watchlist existing = Watchlist.builder()
                .id(5L)
                .ownerId(42L)
                .ownerType(WatchlistOwnerType.CLIENT)
                .name("Old")
                .createdAt(LocalDateTime.now())
                .build();
        when(watchlistRepo.findByIdAndOwnerIdAndOwnerType(5L, 42L, WatchlistOwnerType.CLIENT))
                .thenReturn(Optional.of(existing));
        when(watchlistRepo.existsByOwnerIdAndOwnerTypeAndName(42L, WatchlistOwnerType.CLIENT, "New"))
                .thenReturn(false);
        Watchlist saved = Watchlist.builder()
                .id(5L)
                .ownerId(42L)
                .ownerType(WatchlistOwnerType.CLIENT)
                .name("New")
                .createdAt(existing.getCreatedAt())
                .build();
        when(watchlistRepo.save(any(Watchlist.class))).thenReturn(saved);
        // P2-perf-nplus1-1 (R1 514): itemCount sad ide preko countByWatchlistId (COUNT upit),
        // ne preko load-full-list-then-.size().
        when(itemRepo.countByWatchlistId(5L)).thenReturn(2L);

        CreateWatchlistDto dto = CreateWatchlistDto.builder().name("New").build();
        WatchlistDto res = watchlistService.renameWatchlist(5L, dto);

        assertEquals("New", res.getName());
        assertEquals(2, res.getItemCount());
    }

    @Test
    @DisplayName("renameWatchlist_throwsIfDuplicateName")
    void renameWatchlist_throwsIfDuplicateName() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        Watchlist existing = Watchlist.builder().id(6L).ownerId(42L).ownerType(WatchlistOwnerType.CLIENT).name("A").createdAt(LocalDateTime.now()).build();
        when(watchlistRepo.findByIdAndOwnerIdAndOwnerType(6L, 42L, WatchlistOwnerType.CLIENT)).thenReturn(Optional.of(existing));
        when(watchlistRepo.existsByOwnerIdAndOwnerTypeAndName(42L, WatchlistOwnerType.CLIENT, "B")).thenReturn(true);

        CreateWatchlistDto dto = CreateWatchlistDto.builder().name("B").build();
        assertThatThrownBy(() -> watchlistService.renameWatchlist(6L, dto))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("renameWatchlist_throwsIfWatchlistNotFound")
    void renameWatchlist_throwsIfWatchlistNotFound() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        when(watchlistRepo.findByIdAndOwnerIdAndOwnerType(7L, 42L, WatchlistOwnerType.CLIENT)).thenReturn(Optional.empty());

        CreateWatchlistDto dto = CreateWatchlistDto.builder().name("Whatever").build();
        assertThatThrownBy(() -> watchlistService.renameWatchlist(7L, dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Lista ne postoji");
    }

    @Test
    @DisplayName("deleteWatchlist_success")
    void deleteWatchlist_success() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        Watchlist existing = Watchlist.builder().id(8L).ownerId(42L).ownerType(WatchlistOwnerType.CLIENT).name("X").createdAt(LocalDateTime.now()).build();
        when(watchlistRepo.findByIdAndOwnerIdAndOwnerType(8L, 42L, WatchlistOwnerType.CLIENT)).thenReturn(Optional.of(existing));

        watchlistService.deleteWatchlist(8L);

        verify(itemRepo).deleteAllByWatchlistId(8L);
        verify(watchlistRepo).delete(existing);
    }

    @Test
    @DisplayName("addItem_success")
    void addItem_success() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        Watchlist existing = Watchlist.builder().id(9L).ownerId(42L).ownerType(WatchlistOwnerType.CLIENT).name("L").createdAt(LocalDateTime.now()).build();
        when(watchlistRepo.findByIdAndOwnerIdAndOwnerType(9L, 42L, WatchlistOwnerType.CLIENT)).thenReturn(Optional.of(existing));
        when(itemRepo.existsByWatchlistIdAndListingId(9L, 10L)).thenReturn(false);

        ListingDto listing = new ListingDto();
        listing.setId(10L);
        listing.setTicker("AAPL");
        listing.setName("Apple Inc.");
        listing.setExchangeAcronym("NASDAQ");
        listing.setListingType("STOCK");
        listing.setPrice(new BigDecimal("150.25"));
        listing.setChangePercent(new BigDecimal("1.5"));
        listing.setVolume(1234567L);

        when(listingService.getListingById(10L)).thenReturn(listing);

        WatchlistItem savedItem = WatchlistItem.builder().id(11L).watchlistId(9L).listingId(10L).addedAt(LocalDateTime.now()).build();
        when(itemRepo.save(any(WatchlistItem.class))).thenReturn(savedItem);

        WatchlistItemDto dto = watchlistService.addItem(9L, 10L);

        assertEquals(11L, dto.getId());
        assertEquals(9L, dto.getWatchlistId());
        assertEquals(10L, dto.getListingId());
        assertEquals("AAPL", dto.getTicker());
        assertEquals("Apple Inc.", dto.getListingName());
        assertEquals("STOCK", dto.getSecurityType());
        assertEquals(new BigDecimal("150.25"), dto.getCurrentPrice());
    }

    @Test
    @DisplayName("addItem_throwsIfAlreadyOnList")
    void addItem_throwsIfAlreadyOnList() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        Watchlist existing = Watchlist.builder().id(12L).ownerId(42L).ownerType(WatchlistOwnerType.CLIENT).name("L").createdAt(LocalDateTime.now()).build();
        when(watchlistRepo.findByIdAndOwnerIdAndOwnerType(12L, 42L, WatchlistOwnerType.CLIENT)).thenReturn(Optional.of(existing));
        when(itemRepo.existsByWatchlistIdAndListingId(12L, 20L)).thenReturn(true);

        assertThatThrownBy(() -> watchlistService.addItem(12L, 20L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Hartija je vec na listi");
    }

    @Test
    @DisplayName("removeItem_success")
    void removeItem_success() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        Watchlist existing = Watchlist.builder().id(13L).ownerId(42L).ownerType(WatchlistOwnerType.CLIENT).name("L").createdAt(LocalDateTime.now()).build();
        when(watchlistRepo.findByIdAndOwnerIdAndOwnerType(13L, 42L, WatchlistOwnerType.CLIENT)).thenReturn(Optional.of(existing));
        WatchlistItem item = WatchlistItem.builder().id(21L).watchlistId(13L).listingId(30L).addedAt(LocalDateTime.now()).build();
        when(itemRepo.findByWatchlistIdAndListingId(13L, 30L)).thenReturn(Optional.of(item));

        watchlistService.removeItem(13L, 30L);

        verify(itemRepo).deleteByWatchlistIdAndListingId(13L, 30L);
    }

    @Test
    @DisplayName("listItems_filterBySecurityType_returnsOnlyMatchingItems")
    void listItems_filterBySecurityType_returnsOnlyMatchingItems() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        Watchlist existing = Watchlist.builder().id(14L).ownerId(42L).ownerType(WatchlistOwnerType.CLIENT).name("L").createdAt(LocalDateTime.now()).build();
        when(watchlistRepo.findByIdAndOwnerIdAndOwnerType(14L, 42L, WatchlistOwnerType.CLIENT)).thenReturn(Optional.of(existing));

        WatchlistItem it1 = WatchlistItem.builder().id(31L).watchlistId(14L).listingId(101L).addedAt(LocalDateTime.now()).build();
        WatchlistItem it2 = WatchlistItem.builder().id(32L).watchlistId(14L).listingId(102L).addedAt(LocalDateTime.now()).build();
        WatchlistItem it3 = WatchlistItem.builder().id(33L).watchlistId(14L).listingId(103L).addedAt(LocalDateTime.now()).build();
        when(itemRepo.findByWatchlistIdOrderByAddedAtAsc(14L)).thenReturn(Arrays.asList(it1, it2, it3));

        ListingDto l1 = new ListingDto(); l1.setId(101L); l1.setListingType("STOCK"); l1.setTicker("T1"); l1.setName("N1"); l1.setPrice(new BigDecimal("10")); l1.setChangePercent(new BigDecimal("1")); l1.setVolume(100L);
        ListingDto l2 = new ListingDto(); l2.setId(102L); l2.setListingType("STOCK"); l2.setTicker("T2"); l2.setName("N2"); l2.setPrice(new BigDecimal("20")); l2.setChangePercent(new BigDecimal("2")); l2.setVolume(200L);
        ListingDto l3 = new ListingDto(); l3.setId(103L); l3.setListingType("FOREX"); l3.setTicker("T3"); l3.setName("N3"); l3.setPrice(new BigDecimal("30")); l3.setChangePercent(new BigDecimal("3")); l3.setVolume(300L);

        // P2-perf-nplus1-1 (R1 515): listItems sad batch-resolve-uje sve listinge
        // jednim getListingsByIds (ne per-item getListingById).
        java.util.Map<Long, ListingDto> byId = new java.util.HashMap<>();
        byId.put(101L, l1); byId.put(102L, l2); byId.put(103L, l3);
        when(listingService.getListingsByIds(anyCollection())).thenReturn(byId);

        List<WatchlistItemDto> res = watchlistService.listItems(14L, "STOCK");
        assertEquals(2, res.size());
        assertThat(res).allMatch(d -> "STOCK".equalsIgnoreCase(d.getSecurityType()));
    }

    @Test
    @DisplayName("listItems_batchResolvesListings_singleCall_R1_515")
    void listItems_batchResolvesListings_singleCall_R1_515() {
        // P2-perf-nplus1-1 (R1 515): strukturalna asercija — batch-resolve koristi
        // TACNO JEDAN getListingsByIds poziv, NIKAD per-item getListingById (DB N+1).
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        Watchlist existing = Watchlist.builder().id(15L).ownerId(42L).ownerType(WatchlistOwnerType.CLIENT).name("L").createdAt(LocalDateTime.now()).build();
        when(watchlistRepo.findByIdAndOwnerIdAndOwnerType(15L, 42L, WatchlistOwnerType.CLIENT)).thenReturn(Optional.of(existing));

        WatchlistItem it1 = WatchlistItem.builder().id(41L).watchlistId(15L).listingId(201L).addedAt(LocalDateTime.now()).build();
        WatchlistItem it2 = WatchlistItem.builder().id(42L).watchlistId(15L).listingId(202L).addedAt(LocalDateTime.now()).build();
        when(itemRepo.findByWatchlistIdOrderByAddedAtAsc(15L)).thenReturn(Arrays.asList(it1, it2));

        ListingDto l1 = new ListingDto(); l1.setId(201L); l1.setListingType("STOCK"); l1.setTicker("A"); l1.setName("NA"); l1.setPrice(new BigDecimal("1")); l1.setChangePercent(new BigDecimal("0")); l1.setVolume(1L);
        ListingDto l2 = new ListingDto(); l2.setId(202L); l2.setListingType("STOCK"); l2.setTicker("B"); l2.setName("NB"); l2.setPrice(new BigDecimal("2")); l2.setChangePercent(new BigDecimal("0")); l2.setVolume(2L);
        java.util.Map<Long, ListingDto> byId = new java.util.HashMap<>();
        byId.put(201L, l1); byId.put(202L, l2);
        when(listingService.getListingsByIds(anyCollection())).thenReturn(byId);

        List<WatchlistItemDto> res = watchlistService.listItems(15L, null);

        assertEquals(2, res.size());
        verify(listingService, times(1)).getListingsByIds(anyCollection());
        verify(listingService, never()).getListingById(any());
    }

    // ── [TEST-tr-watchlist-recurring-influx-misc-1 / OT-1177] listItems filter:
    //    case-insensitivity + blank == bez filtera (pin postojeceg ponasanja) ───

    @Test
    @DisplayName("listItems_filterLowercaseSecurityType_matchesCaseInsensitively_OT_1177")
    void listItems_filterLowercaseSecurityType_matchesCaseInsensitively_OT_1177() {
        // OT-1177: securityTypeFilter se poredi preko equalsIgnoreCase — "stock"
        // (mala slova) mora da uhvati "STOCK" listinge. Postojeci test koristi
        // "STOCK" (velika); ovo pina case-insensitivnost eksplicitno.
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        Watchlist existing = Watchlist.builder().id(16L).ownerId(42L).ownerType(WatchlistOwnerType.CLIENT).name("L").createdAt(LocalDateTime.now()).build();
        when(watchlistRepo.findByIdAndOwnerIdAndOwnerType(16L, 42L, WatchlistOwnerType.CLIENT)).thenReturn(Optional.of(existing));

        WatchlistItem it1 = WatchlistItem.builder().id(51L).watchlistId(16L).listingId(301L).addedAt(LocalDateTime.now()).build();
        WatchlistItem it2 = WatchlistItem.builder().id(52L).watchlistId(16L).listingId(302L).addedAt(LocalDateTime.now()).build();
        when(itemRepo.findByWatchlistIdOrderByAddedAtAsc(16L)).thenReturn(Arrays.asList(it1, it2));

        ListingDto stock = new ListingDto(); stock.setId(301L); stock.setListingType("STOCK"); stock.setTicker("S"); stock.setName("NS"); stock.setPrice(new BigDecimal("10")); stock.setChangePercent(new BigDecimal("1")); stock.setVolume(10L);
        ListingDto forex = new ListingDto(); forex.setId(302L); forex.setListingType("FOREX"); forex.setTicker("F"); forex.setName("NF"); forex.setPrice(new BigDecimal("20")); forex.setChangePercent(new BigDecimal("2")); forex.setVolume(20L);
        java.util.Map<Long, ListingDto> byId = new java.util.HashMap<>();
        byId.put(301L, stock); byId.put(302L, forex);
        when(listingService.getListingsByIds(anyCollection())).thenReturn(byId);

        List<WatchlistItemDto> res = watchlistService.listItems(16L, "stock"); // lowercase
        assertEquals(1, res.size());
        assertEquals("STOCK", res.get(0).getSecurityType());
    }

    @Test
    @DisplayName("listItems_blankFilter_returnsAllItems_OT_1177")
    void listItems_blankFilter_returnsAllItems_OT_1177() {
        // OT-1177: filter koji je blank ("   ") tretira se kao "bez filtera" —
        // vraca SVE stavke (kao i null). Pin da praznine/space ne filtriraju sve.
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        Watchlist existing = Watchlist.builder().id(17L).ownerId(42L).ownerType(WatchlistOwnerType.CLIENT).name("L").createdAt(LocalDateTime.now()).build();
        when(watchlistRepo.findByIdAndOwnerIdAndOwnerType(17L, 42L, WatchlistOwnerType.CLIENT)).thenReturn(Optional.of(existing));

        WatchlistItem it1 = WatchlistItem.builder().id(61L).watchlistId(17L).listingId(401L).addedAt(LocalDateTime.now()).build();
        WatchlistItem it2 = WatchlistItem.builder().id(62L).watchlistId(17L).listingId(402L).addedAt(LocalDateTime.now()).build();
        when(itemRepo.findByWatchlistIdOrderByAddedAtAsc(17L)).thenReturn(Arrays.asList(it1, it2));

        ListingDto stock = new ListingDto(); stock.setId(401L); stock.setListingType("STOCK"); stock.setTicker("S"); stock.setName("NS"); stock.setPrice(new BigDecimal("10")); stock.setChangePercent(new BigDecimal("1")); stock.setVolume(10L);
        ListingDto forex = new ListingDto(); forex.setId(402L); forex.setListingType("FOREX"); forex.setTicker("F"); forex.setName("NF"); forex.setPrice(new BigDecimal("20")); forex.setChangePercent(new BigDecimal("2")); forex.setVolume(20L);
        java.util.Map<Long, ListingDto> byId = new java.util.HashMap<>();
        byId.put(401L, stock); byId.put(402L, forex);
        when(listingService.getListingsByIds(anyCollection())).thenReturn(byId);

        List<WatchlistItemDto> res = watchlistService.listItems(17L, "   "); // blank
        assertEquals(2, res.size());
    }

    // ── [TEST-tr-watchlist-recurring-influx-misc-1 / OT-1178] cross-owner authz:
    //    vlasnistvo se proverava po (ownerId, ownerType) — EMPLOYEE ne moze citati
    //    CLIENT listu sa istim numerickim id-em (razliciti namespace-ovi). ──────

    @Test
    @DisplayName("listItems_crossOwnerType_employeeCannotReadClientList_OT_1178")
    void listItems_crossOwnerType_employeeCannotReadClientList_OT_1178() {
        // OT-1178: zaposleni (#42 u EMPLOYEE namespace-u) NE sme da cita listu
        // klijenta #42. resolveOwnerType za EMPLOYEE daje EMPLOYEE; repo upit
        // findByIdAndOwnerIdAndOwnerType(.., EMPLOYEE) ne nalazi CLIENT listu → throw.
        UserContext employeeCtx = new UserContext(42L, UserRole.EMPLOYEE);
        when(userResolver.resolveCurrent()).thenReturn(employeeCtx);
        when(watchlistRepo.findByIdAndOwnerIdAndOwnerType(70L, 42L, WatchlistOwnerType.EMPLOYEE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> watchlistService.listItems(70L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nije vasa");
        // Nikoga ne resolve-ujemo iz ListingService-a (rano se baca).
        verify(listingService, never()).getListingsByIds(anyCollection());
    }

    @Test
    @DisplayName("addItem_crossOwnerType_clientCannotMutateEmployeeList_OT_1178")
    void addItem_crossOwnerType_clientCannotMutateEmployeeList_OT_1178() {
        // OT-1178 (mutacija): CLIENT #42 ne sme da doda stavku na EMPLOYEE listu —
        // ownerType discriminator u repo upitu sprecava (findByIdAndOwnerIdAndOwnerType
        // sa CLIENT ne nalazi EMPLOYEE listu).
        when(userResolver.resolveCurrent()).thenReturn(clientCtx); // CLIENT/42
        when(watchlistRepo.findByIdAndOwnerIdAndOwnerType(71L, 42L, WatchlistOwnerType.CLIENT))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> watchlistService.addItem(71L, 500L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nije vasa");
        verify(itemRepo, never()).save(any());
    }
}