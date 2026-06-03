package rs.raf.trading.stock.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import rs.raf.trading.berza.repository.ExchangeRepository;
import rs.raf.trading.stock.dto.ListingDto;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;
import rs.raf.trading.stock.service.implementation.ListingServiceImpl;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sc25 (Celina 3, TestoviCelina3 §25): "Prikaz hartija sa nepoznatog exchange-a".
 * Given korisnik je na listi hartija; When sistem ucita hartije sa nepostojecim
 * exchange-om; Then takve hartije se NE prikazuju.
 *
 * <p>DB-backed test (H2 u PostgreSQL modu, isti obrazac kao MarginAccountRepository*
 * testovi) jer Sc25 zahteva da se orphan-exchange listing iskljuci na DB nivou preko
 * {@code ListingSpec.tradeableExchange()} EXISTS sub-upita — mock-CriteriaBuilder
 * unit test (ListingSpecTest) ne moze da dokaze da SQL zapravo iskljucuje orphan red.
 *
 * <p>Berze (NYSE/NASDAQ/... 7 komada) seeduje {@code ExchangeSeedData} pri pokretanju
 * konteksta — koristimo NYSE/NASDAQ kao postojece (poznate) berze. Listings tabelu
 * ciscimo u {@code @BeforeEach} da seed/prethodni testovi ne uticu, pa ubacujemo:
 * dva listinga na poznatim berzama + jedan na orphan acronym-u "GHOST" + jedan sa
 * null acronym-om. Oba orphan-a moraju izostati iz {@code getListings} rezultata.
 */
@SpringBootTest
@ActiveProfiles("test")
class ListingOrphanExchangeFilterIT {

    @Autowired
    private ListingServiceImpl listingService;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private ExchangeRepository exchangeRepository;

    @BeforeEach
    void cleanListings() {
        // Seed/prethodni testovi mogu ostaviti listinge — krećemo od cistog stanja.
        listingRepository.deleteAll();
        // Sanity: NYSE/NASDAQ su seed-ovani (ExchangeSeedData). Ako iz nekog razloga
        // nisu (npr. neko ih je obrisao u drugom testu), test bi davao lazne rezultate.
        assertThat(exchangeRepository.findByAcronym("NYSE")).isPresent();
        assertThat(exchangeRepository.findByAcronym("NASDAQ")).isPresent();
        // "GHOST" NE sme postojati kao berza (to je nasa orphan referenca).
        assertThat(exchangeRepository.findByAcronym("GHOST")).isEmpty();
    }

    @AfterEach
    void tearDown() {
        listingRepository.deleteAll();
        SecurityContextHolder.clearContext();
    }

    private void authAsEmployee() {
        var auth = new UsernamePasswordAuthenticationToken(
                "emp", "n/a", List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    private Listing listing(String ticker, String exchangeAcronym) {
        Listing l = new Listing();
        l.setTicker(ticker);
        l.setName(ticker + " Corp");
        l.setExchangeAcronym(exchangeAcronym);
        l.setListingType(ListingType.STOCK);
        l.setPrice(new BigDecimal("100.0000"));
        l.setPriceChange(new BigDecimal("1.0000"));
        return listingRepository.save(l);
    }

    @Test
    @DisplayName("Sc25: listing sa nepostojecim (orphan) exchange-om se NE prikazuje u listi")
    void orphanExchangeListing_isExcludedFromListing() {
        listing("AAPL", "NASDAQ");
        listing("IBM", "NYSE");
        // Orphan: "GHOST" nije seed-ovana berza -> mora izostati.
        listing("GHST", "GHOST");

        authAsEmployee();
        Page<ListingDto> result = listingService.getListings("STOCK", null, 0, 50);

        assertThat(result.getContent())
                .extracting(ListingDto::getTicker)
                .containsExactlyInAnyOrder("AAPL", "IBM")
                .doesNotContain("GHST");
        // Paginacija/total su tacni — orphan NIJE ubrojan pa skinut iz sadrzaja.
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("Sc25: listing sa null exchange acronym-om se takodje NE prikazuje")
    void nullExchangeAcronymListing_isExcluded() {
        listing("IBM", "NYSE");
        listing("ORPHAN_NULL", null);

        authAsEmployee();
        Page<ListingDto> result = listingService.getListings("STOCK", null, 0, 50);

        assertThat(result.getContent())
                .extracting(ListingDto::getTicker)
                .containsExactly("IBM");
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("Sc25: kad SVE hartije imaju poznatu berzu, sve se prikazuju (regresija — filter ne presecu validne)")
    void allKnownExchanges_allShown() {
        listing("AAPL", "NASDAQ");
        listing("IBM", "NYSE");
        listing("MSFT", "NASDAQ");

        authAsEmployee();
        Page<ListingDto> result = listingService.getListings("STOCK", null, 0, 50);

        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getContent())
                .extracting(ListingDto::getTicker)
                .containsExactlyInAnyOrder("AAPL", "IBM", "MSFT");
    }
}
