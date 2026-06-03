package rs.raf.trading.option.service;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.option.model.Option;
import rs.raf.trading.option.model.OptionType;
import rs.raf.trading.option.repository.OptionRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [TEST-tr-watchlist-recurring-influx-misc-1 / OT-896] N+1 perf probe za
 * {@code recalculatePrices}-stil iteraciju nad opcijama.
 *
 * <p>{@code Option.stockListing} je {@code @ManyToOne(fetch = LAZY)}. Petlja u
 * {@code OptionMaintenanceService.recalculatePrices} cita
 * {@code option.getStockListing().getPrice()} po opciji. Ako se opcije ucitaju
 * obicnim {@code findAll()} (bez join-fetch-a), svaki {@code getStockListing()}
 * okida zaseban SELECT → klasican N+1 (1 + N upita).
 *
 * <p>Ovaj test koristi Hibernate {@link Statistics} da PREBROJI SELECT-ove.
 * Posle fix-a (join-fetch {@code findAllWithStockListing}) ocekujemo JEDAN upit
 * (eager join), ne 1+N. {@code @SpringBootTest + H2 + @ActiveProfiles("test")}
 * je obrazac celog modula (Spring Boot 4 — nema {@code @DataJpaTest}).
 */
@SpringBootTest
@ActiveProfiles("test")
class OptionMaintenanceNPlusOneTest {

    @Autowired private OptionRepository optionRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private EntityManagerFactory emf;

    @AfterEach
    void tearDown() {
        optionRepository.deleteAll();
        listingRepository.deleteAll();
    }

    private Listing savedListing(String ticker, String price) {
        Listing l = new Listing();
        l.setTicker(ticker);
        l.setName(ticker + " Inc.");
        l.setListingType(ListingType.STOCK);
        l.setPrice(new BigDecimal(price));
        l.setExchangeAcronym("NASDAQ");
        return listingRepository.save(l);
    }

    private void savedOption(Listing listing, String ticker) {
        Option o = new Option();
        o.setStockListing(listing);
        o.setOptionType(OptionType.CALL);
        o.setStrikePrice(new BigDecimal("100.0000"));
        o.setImpliedVolatility(0.25);
        o.setSettlementDate(LocalDate.now().plusDays(30));
        o.setContractSize(100);
        o.setPrice(new BigDecimal("10.0000"));
        o.setTicker(ticker);
        optionRepository.save(o);
    }

    @Test
    @DisplayName("OT-896: findAllWithStockListing eager-fetch-uje listinge u JEDNOM upitu (nije N+1)")
    @Transactional
    void recalculateScan_doesNotTriggerNPlusOne() {
        // 3 opcije, svaka sa DISTINKTNIM listingom → bez join-fetch-a bi to bilo
        // 1 (options) + 3 (per-option lazy stockListing) = 4 SELECT-a.
        Listing l1 = savedListing("AAA", "110");
        Listing l2 = savedListing("BBB", "120");
        Listing l3 = savedListing("CCC", "130");
        savedOption(l1, "OPT-AAA");
        savedOption(l2, "OPT-BBB");
        savedOption(l3, "OPT-CCC");

        SessionFactory sessionFactory = emf.unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        long before = stats.getPrepareStatementCount();

        // Mirror recalculatePrices scan: ucitaj opcije + procitaj cenu osnovne akcije.
        List<Option> options = optionRepository.findAllWithStockListing();
        BigDecimal sum = BigDecimal.ZERO;
        for (Option option : options) {
            BigDecimal stockPrice = option.getStockListing().getPrice();
            if (stockPrice != null) {
                sum = sum.add(stockPrice);
            }
        }

        long queries = stats.getPrepareStatementCount() - before;

        assertThat(options).hasSize(3);
        assertThat(sum).isEqualByComparingTo("360"); // 110+120+130
        // Join-fetch: SVE opcije + njihovi listinzi u JEDNOM upitu (ne 1+N).
        assertThat(queries)
                .as("recalculate scan ucitava opcije+listinge jednim join-fetch upitom (ne N+1)")
                .isEqualTo(1L);
    }
}
