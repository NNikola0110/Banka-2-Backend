package rs.raf.trading.portfolio.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.portfolio.dto.PortfolioItemDto;
import rs.raf.trading.portfolio.dto.PortfolioSummaryDto;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.repository.ListingRepository;
import rs.raf.trading.tax.model.TaxRecord;
import rs.raf.trading.tax.repository.TaxRecordRepository;
import rs.raf.trading.tax.util.TaxConstants;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * NAPOMENA (copy-first ekstrakcija, faza 2c): monolitna implementacija je
 * razresavala identitet trenutnog korisnika citajuci {@code clients}/{@code employees}
 * tabele direktno (privatni {@code OwnerRef} record + {@code getCurrentOwner()}).
 * U trading-service-u identitet (numericki id + rola) razresava
 * {@link TradingUserResolver} preko banka-core internog API-ja. Portfolio ne
 * dira novac — samo identitet — pa ovde nema banka-core funds seam-a.
 */
@Service
@RequiredArgsConstructor
public class PortfolioService {

    private final PortfolioRepository portfolioRepository;
    private final ListingRepository listingRepository;
    private final TaxRecordRepository taxRecordRepository;
    private final TradingUserResolver userResolver;

    /**
     * Vraca listu portfolio stavki za trenutnog korisnika sa izracunatim profitom.
     */
    public List<PortfolioItemDto> getMyPortfolio() {
        UserContext owner = userResolver.resolveCurrent();
        List<Portfolio> portfolios = portfolioRepository.findByUserIdAndUserRole(owner.userId(), owner.userRole());

        return portfolios.stream().map(p -> buildItemDto(p, getCurrentPrice(p.getListingId()), true)).toList();
    }

    /**
     * R1-728: jedinstvena izgradnja {@link PortfolioItemDto} (profit / profitPercent /
     * polja) iz {@link Portfolio} + trenutne cene — DRY izmedju {@link #getMyPortfolio}
     * i {@link #setPublicQuantity} (pre konsolidacije identican blok kopiran dvaput).
     *
     * @param withSettlementItm kad je {@code true}, dodatno popunjava settlementDate +
     *                          inTheMoney iz listinga (jedan {@code findById}); to radi
     *                          samo lista-put ({@code getMyPortfolio}), ne single-update
     *                          put koji istorijski nije vracao ITM.
     */
    private PortfolioItemDto buildItemDto(Portfolio p, BigDecimal currentPrice, boolean withSettlementItm) {
        BigDecimal avgPrice = p.getAverageBuyPrice();
        BigDecimal qty = BigDecimal.valueOf(p.getQuantity());

        // profit = (currentPrice - avgPrice) * quantity
        BigDecimal profit = currentPrice.subtract(avgPrice).multiply(qty);

        // profitPercent = ((currentPrice - avgPrice) / avgPrice) * 100
        BigDecimal profitPercent = BigDecimal.ZERO;
        if (avgPrice.compareTo(BigDecimal.ZERO) != 0) {
            profitPercent = currentPrice.subtract(avgPrice)
                    .divide(avgPrice, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        PortfolioItemDto dto = new PortfolioItemDto();
        dto.setId(p.getId());
        dto.setListingId(p.getListingId());
        dto.setListingTicker(p.getListingTicker());
        dto.setListingName(p.getListingName());
        dto.setListingType(p.getListingType());
        dto.setQuantity(p.getQuantity());
        dto.setAverageBuyPrice(avgPrice);
        dto.setCurrentPrice(currentPrice);
        dto.setProfit(profit);
        dto.setProfitPercent(profitPercent);
        dto.setPublicQuantity(p.getPublicQuantity());
        dto.setLastModified(p.getLastModified());

        // Settlement date i ITM iz listinga.
        //
        // R1-173 (CORRECTNESS): "In-the-money" je pojam vezan za instrumente sa
        // istekom (settlementDate) — futures/opcioni-tip. Portfolio drzi ISKLJUCIVO
        // LONG pozicije (kupljene hartije: STOCK/FUTURES/FOREX), pa je za pozicije sa
        // istekom ITM == "pozicija trenutno vredi vise nego sto je placeno"
        // (currentPrice > averageBuyPrice). Za obicne akcije BEZ settlementDate-a
        // (vecina portfolija) "ITM" nema smisla — ostaje null (FE/Mobile ga onda
        // ne prikazuju). Stara verzija je ITM postavljala na SVE tipove uz
        // zavaravajuci PUT/CALL komentar (portfolio nikad ne drzi short ni opcioni
        // ugovor — to su zasebni Option entiteti) — pa je obicna akcija "u profitu"
        // pogresno dobijala ITM badge.
        if (withSettlementItm) {
            Optional<Listing> listingOpt = listingRepository.findById(p.getListingId());
            if (listingOpt.isPresent()) {
                Listing listing = listingOpt.get();
                dto.setSettlementDate(listing.getSettlementDate());
                if (listing.getSettlementDate() != null) {
                    // Instrument sa istekom (futures/opcioni-tip) — ITM za long poziciju.
                    dto.setInTheMoney(currentPrice.compareTo(avgPrice) > 0);
                }
                // Obicna akcija (settlementDate == null) → inTheMoney ostaje null.
            }
        }

        return dto;
    }

    /**
     * Vraca sumarni pregled portfolija (ukupna vrednost, profit, porez).
     */
    public PortfolioSummaryDto getSummary() {
        UserContext owner = userResolver.resolveCurrent();
        List<PortfolioItemDto> items = getMyPortfolio();

        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;

        for (PortfolioItemDto item : items) {
            BigDecimal itemValue = item.getCurrentPrice()
                    .multiply(BigDecimal.valueOf(item.getQuantity()));
            totalValue = totalValue.add(itemValue);
            totalProfit = totalProfit.add(item.getProfit());
        }

        // Porez na kapitalnu dobit: 15% na pozitivan profit (spec: Celina 3 - Porez).
        // R1-737: koristi centralizovani TaxConstants.computeTax (kanonska politika
        // zaokruzivanja na TAX_SCALE) umesto inline multiply+setScale, da bi prikazani
        // neplaceni porez bio izveden iz iste politike kao porez koji se stvarno
        // naplacuje. Display rounding na 2 decimale primenjuje se na DTO granici
        // (kao i svi ostali novcani iznosi u PortfolioSummaryDto).
        BigDecimal unpaidTax = totalProfit.compareTo(BigDecimal.ZERO) > 0
                ? TaxConstants.computeTax(totalProfit).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Dohvati placeni porez iz TaxRecord-a
        BigDecimal paidTaxThisYear = BigDecimal.ZERO;
        String userType = owner.isEmployee() ? UserRole.EMPLOYEE : UserRole.CLIENT;
        Optional<TaxRecord> taxRecord = taxRecordRepository.findByUserIdAndUserType(owner.userId(), userType);
        if (taxRecord.isPresent()) {
            paidTaxThisYear = taxRecord.get().getTaxPaid() != null
                    ? taxRecord.get().getTaxPaid() : BigDecimal.ZERO;
            // Neplacen porez = dugovanje - vec placeno
            BigDecimal taxOwed = taxRecord.get().getTaxOwed() != null
                    ? taxRecord.get().getTaxOwed() : BigDecimal.ZERO;
            BigDecimal remaining = taxOwed.subtract(paidTaxThisYear);
            unpaidTax = remaining.compareTo(BigDecimal.ZERO) > 0
                    ? remaining.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        }

        return new PortfolioSummaryDto(
                totalValue.setScale(2, RoundingMode.HALF_UP),
                totalProfit.setScale(2, RoundingMode.HALF_UP),
                paidTaxThisYear.setScale(2, RoundingMode.HALF_UP),
                unpaidTax
        );
    }

    /**
     * Azurira javnu kolicinu za datu portfolio stavku.
     * Vraca azuriranu stavku.
     */
    @Transactional
    public PortfolioItemDto setPublicQuantity(Long portfolioId, int quantity) {
        UserContext owner = userResolver.resolveCurrent();

        // R1 422: jasni HTTP statusi umesto bare RuntimeException→400 catch-all.
        Portfolio portfolio = portfolioRepository.findById(portfolioId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Portfolio stavka nije pronadjena: " + portfolioId)); // → 404

        if (!portfolio.getUserId().equals(owner.userId())
                || (portfolio.getUserRole() != null && !portfolio.getUserRole().equals(owner.userRole()))) {
            throw new AccessDeniedException("Nemate pristup ovoj portfolio stavci."); // → 403
        }

        if (quantity < 0 || quantity > portfolio.getQuantity()) {
            throw new IllegalArgumentException(
                    "Javna kolicina mora biti izmedju 0 i " + portfolio.getQuantity());
        }

        portfolio.setPublicQuantity(quantity);
        portfolioRepository.save(portfolio);

        // Vrati azuriranu stavku (R1-728: deljeni buildItemDto; ovaj put istorijski
        // NE vraca settlementDate/inTheMoney → withSettlementItm=false, ponasanje
        // ostaje byte-identicno + stedi se jedan listing findById).
        return buildItemDto(portfolio, getCurrentPrice(portfolio.getListingId()), false);
    }

    /**
     * Vraca trenutnu cenu listinga iz baze. Fallback na 0 ako listing ne postoji.
     */
    private BigDecimal getCurrentPrice(Long listingId) {
        Optional<Listing> listing = listingRepository.findById(listingId);
        return listing.map(l -> l.getPrice() != null ? l.getPrice() : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);
    }
}
