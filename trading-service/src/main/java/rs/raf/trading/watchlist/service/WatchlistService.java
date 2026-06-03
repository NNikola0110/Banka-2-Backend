package rs.raf.trading.watchlist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.dto.ListingDto;
import rs.raf.trading.stock.service.ListingService;
import rs.raf.trading.watchlist.dto.CreateWatchlistDto;
import rs.raf.trading.watchlist.dto.WatchlistDto;
import rs.raf.trading.watchlist.dto.WatchlistItemDto;
import rs.raf.trading.watchlist.model.Watchlist;
import rs.raf.trading.watchlist.model.WatchlistItem;
import rs.raf.trading.watchlist.model.WatchlistOwnerType;
import rs.raf.trading.watchlist.repository.WatchlistItemRepository;
import rs.raf.trading.watchlist.repository.WatchlistRepository;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WatchlistService {

    /** [P2-input-validation-1 / R1 517] gornje granice za DoS guard. */
    public static final int MAX_WATCHLISTS_PER_USER = 20;
    public static final int MAX_ITEMS_PER_WATCHLIST = 100;

    private final WatchlistRepository watchlistRepo;
    private final WatchlistItemRepository itemRepo;
    private final TradingUserResolver userResolver;
    private final ListingService listingService;

    @Transactional
    public WatchlistDto createWatchlist(CreateWatchlistDto dto) {
        UserContext me = userResolver.resolveCurrent();
        WatchlistOwnerType ownerType = resolveOwnerType(me);
        // [P2-input-validation-1 / R1 517] limit broja listi po korisniku (DoS guard).
        if (watchlistRepo.countByOwnerIdAndOwnerType(me.userId(), ownerType) >= MAX_WATCHLISTS_PER_USER) {
            throw new IllegalArgumentException(
                    "Dostigli ste maksimalan broj lista (" + MAX_WATCHLISTS_PER_USER + ").");
        }
        if (watchlistRepo.existsByOwnerIdAndOwnerTypeAndName(me.userId(), ownerType, dto.getName())) {
            throw new IllegalArgumentException("Lista sa imenom vec postoji: " + dto.getName());
        }
        Watchlist wl = Watchlist.builder()
                .ownerId(me.userId())
                .ownerType(ownerType)
                .name(dto.getName())
                .build();
        Watchlist saved = watchlistRepo.save(wl);
        return WatchlistDto.builder()
                .id(saved.getId())
                .ownerId(saved.getOwnerId())
                .ownerType(saved.getOwnerType().name())
                .name(saved.getName())
                .itemCount(0)
                .createdAt(saved.getCreatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public List<WatchlistDto> listMyWatchlists() {
        UserContext me = userResolver.resolveCurrent();
        WatchlistOwnerType ownerType = resolveOwnerType(me);
        List<Watchlist> lists = watchlistRepo.findByOwnerIdAndOwnerTypeOrderByCreatedAtAsc(me.userId(), ownerType);
        // P2-perf-nplus1-1 (R1 514): broj stavki preko COUNT upita (itemRepo.countByWatchlistId)
        // umesto da ucitavamo CELU listu stavki samo da bismo je prebrojali (.size()).
        // COUNT je laksi na DB + ne materijalizuje entitete koji se odmah bacaju.
        return lists.stream().map(w -> {
            long count = itemRepo.countByWatchlistId(w.getId());
            return WatchlistDto.builder()
                    .id(w.getId())
                    .ownerId(w.getOwnerId())
                    .ownerType(w.getOwnerType().name())
                    .name(w.getName())
                    .itemCount((int) count)
                    .createdAt(w.getCreatedAt())
                    .build();
        }).collect(Collectors.toList());
    }

    @Transactional
    public WatchlistDto renameWatchlist(Long watchlistId, CreateWatchlistDto dto) {
        UserContext me = userResolver.resolveCurrent();
        WatchlistOwnerType ownerType = resolveOwnerType(me);
        Watchlist wl = watchlistRepo.findByIdAndOwnerIdAndOwnerType(watchlistId, me.userId(), ownerType)
                .orElseThrow(() -> new IllegalArgumentException("Lista ne postoji ili nije vasa."));
        if (watchlistRepo.existsByOwnerIdAndOwnerTypeAndName(me.userId(), ownerType, dto.getName())) {
            throw new IllegalArgumentException("Lista sa imenom vec postoji: " + dto.getName());
        }
        wl.setName(dto.getName());
        Watchlist saved = watchlistRepo.save(wl);
        // P2-perf-nplus1-1 (R1 514): COUNT umesto load-full-list-then-.size().
        int count = (int) itemRepo.countByWatchlistId(saved.getId());
        return WatchlistDto.builder()
                .id(saved.getId())
                .ownerId(saved.getOwnerId())
                .ownerType(saved.getOwnerType().name())
                .name(saved.getName())
                .itemCount(count)
                .createdAt(saved.getCreatedAt())
                .build();
    }

    @Transactional
    public void deleteWatchlist(Long watchlistId) {
        UserContext me = userResolver.resolveCurrent();
        WatchlistOwnerType ownerType = resolveOwnerType(me);
        Watchlist wl = watchlistRepo.findByIdAndOwnerIdAndOwnerType(watchlistId, me.userId(), ownerType)
                .orElseThrow(() -> new IllegalArgumentException("Lista ne postoji ili nije vasa."));
        itemRepo.deleteAllByWatchlistId(wl.getId());
        watchlistRepo.delete(wl);
    }

    @Transactional
    public WatchlistItemDto addItem(Long watchlistId, Long listingId) {
        UserContext me = userResolver.resolveCurrent();
        WatchlistOwnerType ownerType = resolveOwnerType(me);
        Watchlist wl = watchlistRepo.findByIdAndOwnerIdAndOwnerType(watchlistId, me.userId(), ownerType)
                .orElseThrow(() -> new IllegalArgumentException("Lista ne postoji ili nije vasa."));
        if (itemRepo.existsByWatchlistIdAndListingId(wl.getId(), listingId)) {
            throw new IllegalArgumentException("Hartija je vec na listi.");
        }
        // [P2-input-validation-1 / R1 517] limit broja stavki po listi (DoS guard).
        if (itemRepo.countByWatchlistId(wl.getId()) >= MAX_ITEMS_PER_WATCHLIST) {
            throw new IllegalArgumentException(
                    "Lista je dostigla maksimalan broj stavki (" + MAX_ITEMS_PER_WATCHLIST + ").");
        }
        // validate listing exists and fetch live data
        ListingDto listing = listingService.getListingById(listingId);

        WatchlistItem item = WatchlistItem.builder()
                .watchlistId(wl.getId())
                .listingId(listingId)
                .build();
        WatchlistItem saved = itemRepo.save(item);
        return toItemDto(saved, listing);
    }

    @Transactional
    public void removeItem(Long watchlistId, Long listingId) {
        UserContext me = userResolver.resolveCurrent();
        WatchlistOwnerType ownerType = resolveOwnerType(me);
        Watchlist wl = watchlistRepo.findByIdAndOwnerIdAndOwnerType(watchlistId, me.userId(), ownerType)
                .orElseThrow(() -> new IllegalArgumentException("Lista ne postoji ili nije vasa."));
        itemRepo.findByWatchlistIdAndListingId(wl.getId(), listingId)
                .orElseThrow(() -> new IllegalArgumentException("Stavka nije pronadjena."));
        itemRepo.deleteByWatchlistIdAndListingId(wl.getId(), listingId);
    }

    @Transactional(readOnly = true)
    public List<WatchlistItemDto> listItems(Long watchlistId, String securityTypeFilter) {
        UserContext me = userResolver.resolveCurrent();
        WatchlistOwnerType ownerType = resolveOwnerType(me);
        Watchlist wl = watchlistRepo.findByIdAndOwnerIdAndOwnerType(watchlistId, me.userId(), ownerType)
                .orElseThrow(() -> new IllegalArgumentException("Lista ne postoji ili nije vasa."));
        List<WatchlistItem> items = itemRepo.findByWatchlistIdOrderByAddedAtAsc(wl.getId());
        // P2-perf-nplus1-1 (R1 515): batch-resolve svih listinga jednim pozivom
        // (getListingsByIds) umesto per-item getListingById (DB N+1).
        java.util.Map<Long, ListingDto> listingsById = listingService.getListingsByIds(
                items.stream().map(WatchlistItem::getListingId).collect(Collectors.toList()));
        return items.stream()
                .map(item -> {
                    ListingDto listing = listingsById.get(item.getListingId());
                    return listing == null ? null : toItemDto(item, listing);
                })
                .filter(java.util.Objects::nonNull)
                .filter(dto -> securityTypeFilter == null || securityTypeFilter.isBlank() || securityTypeFilter.equalsIgnoreCase(dto.getSecurityType()))
                .collect(Collectors.toList());
    }

    private WatchlistOwnerType resolveOwnerType(UserContext me) {
        return me.isClient() ? WatchlistOwnerType.CLIENT : WatchlistOwnerType.EMPLOYEE;
    }

    private WatchlistItemDto toItemDto(WatchlistItem item, ListingDto listing) {
        return WatchlistItemDto.builder()
                .id(item.getId())
                .watchlistId(item.getWatchlistId())
                .listingId(item.getListingId())
                .ticker(listing.getTicker())
                .listingName(listing.getName())
                .securityType(listing.getListingType())
                .exchangeName(listing.getExchangeAcronym())
                .currentPrice(listing.getPrice())
                .dailyChange(listing.getChangePercent())
                .volume(listing.getVolume())
                .addedAt(item.getAddedAt())
                .build();
    }
}