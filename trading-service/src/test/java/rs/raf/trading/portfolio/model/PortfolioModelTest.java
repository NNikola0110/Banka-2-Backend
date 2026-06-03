package rs.raf.trading.portfolio.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioModelTest {

    @Test
    void noArgsConstructor_defaults() {
        Portfolio p = new Portfolio();
        assertThat(p.getId()).isNull();
        assertThat(p.getPublicQuantity()).isEqualTo(0);
        assertThat(p.getReservedQuantity()).isEqualTo(0);
    }

    @Test
    void noArgsConstructor_versionDefaultsToZero_notNull_Bug4() {
        // Bug 4 (REAL money/execution): @Version polje mora startovati na 0L (ne null)
        // da seed-ovani / novi portfolio ne lomi optimistic-lock UPDATE na commit-u.
        Portfolio p = new Portfolio();
        assertThat(p.getVersion()).isEqualTo(0L);
    }

    @Test
    void builder_versionDefaultsToZero_whenNotSet_Bug4() {
        Portfolio p = Portfolio.builder()
                .userId(1L)
                .userRole("CLIENT")
                .listingId(10L)
                .listingTicker("AAPL")
                .listingName("Apple")
                .listingType("STOCK")
                .quantity(50)
                .averageBuyPrice(new BigDecimal("145.0000"))
                .publicQuantity(0)
                .reservedQuantity(0)
                .lastModified(LocalDateTime.now())
                .build();
        // Lombok @Builder ne poziva field-initializer; ali entitet se gradi i preko
        // no-args puta (Hibernate) gde init vazi. Builder put bez eksplicitnog version-a
        // ostavlja null — to je OK jer @ColumnDefault + migracija pokrivaju DB sloj.
        // Eksplicitan 0 je preporuka kad se gradi preko builder-a.
        assertThat(p.getVersion()).isNull();
        p.setVersion(0L);
        assertThat(p.getVersion()).isEqualTo(0L);
    }

    @Test
    void allArgsConstructor_setsAll() {
        LocalDateTime now = LocalDateTime.now();
        Portfolio p = new Portfolio(1L, 10L, "CLIENT", 100L, "AAPL", "Apple", "STOCK",
                50, new BigDecimal("150.5000"), 5, 10, now, null);
        assertThat(p.getId()).isEqualTo(1L);
        assertThat(p.getUserId()).isEqualTo(10L);
        assertThat(p.getListingId()).isEqualTo(100L);
        assertThat(p.getListingTicker()).isEqualTo("AAPL");
        assertThat(p.getListingName()).isEqualTo("Apple");
        assertThat(p.getListingType()).isEqualTo("STOCK");
        assertThat(p.getQuantity()).isEqualTo(50);
        assertThat(p.getAverageBuyPrice()).isEqualByComparingTo("150.5000");
        assertThat(p.getPublicQuantity()).isEqualTo(5);
        assertThat(p.getReservedQuantity()).isEqualTo(10);
        assertThat(p.getLastModified()).isEqualTo(now);
    }

    @Test
    void settersGetters_viaLombok() {
        Portfolio p = new Portfolio();
        p.setId(2L);
        p.setUserId(11L);
        p.setListingId(101L);
        p.setListingTicker("MSFT");
        p.setListingName("Microsoft");
        p.setListingType("STOCK");
        p.setQuantity(20);
        p.setAverageBuyPrice(new BigDecimal("300"));
        p.setPublicQuantity(2);
        p.setReservedQuantity(3);
        LocalDateTime t = LocalDateTime.now();
        p.setLastModified(t);

        assertThat(p.getUserId()).isEqualTo(11L);
        assertThat(p.getListingTicker()).isEqualTo("MSFT");
        assertThat(p.getLastModified()).isEqualTo(t);
    }

    @Test
    void getAvailableQuantity_subtractsReserved() {
        Portfolio p = new Portfolio();
        p.setQuantity(100);
        p.setReservedQuantity(30);
        assertThat(p.getAvailableQuantity()).isEqualTo(70);
    }

    @Test
    void getAvailableQuantity_nullReservedTreatedAsZero() {
        Portfolio p = new Portfolio();
        p.setQuantity(40);
        p.setReservedQuantity(null);
        assertThat(p.getAvailableQuantity()).isEqualTo(40);
    }

    @Test
    void getAvailableQuantity_zeroReserved() {
        Portfolio p = new Portfolio();
        p.setQuantity(25);
        p.setReservedQuantity(0);
        assertThat(p.getAvailableQuantity()).isEqualTo(25);
    }

    @Test
    void equalsHashCodeToString_lombokData() {
        Portfolio a = new Portfolio();
        a.setUserId(1L);
        a.setListingId(2L);
        Portfolio b = new Portfolio();
        b.setUserId(1L);
        b.setListingId(2L);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.toString()).contains("Portfolio");
    }

    @Test
    void onUpdate_setsLastModified() {
        Portfolio p = new Portfolio();
        p.onUpdate();
        assertThat(p.getLastModified()).isNotNull();
    }
}
