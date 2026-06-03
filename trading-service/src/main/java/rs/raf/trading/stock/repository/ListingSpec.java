package rs.raf.trading.stock.repository;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import rs.raf.trading.berza.model.Exchange;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class ListingSpec {

    private ListingSpec() {}

    /**
     * Sc25 (Celina 3, TestoviCelina3 §25): hartije sa nepoznatog (orphan) exchange-a
     * se NE prikazuju. Bilo koji listing cija {@code exchangeAcronym} ne odgovara
     * nijednoj poznatoj berzi (ili je {@code null}) se iskljucuje iz svih listing
     * upita — bez obzira na korisnicki filter.
     *
     * <p>Implementirano kao korelirani EXISTS sub-upit ({@code exchangeAcronym IN
     * (SELECT acronym FROM exchanges)}) umesto kao in-memory filter posle fetch-a:
     * tako se orphan hartije iskljucuju na DB nivou (paginacija/brojanje su tacni —
     * in-memory bi izbrojao orphan u {@code totalElements} pa skinuo iz sadrzaja,
     * lazuci page metapodatke). {@code null} acronym ne moze da bude {@code IN}
     * praznog/ne-null skupa pa je takodje iskljucen.
     */
    public static Specification<Listing> tradeableExchange() {
        return (root, query, cb) -> {
            Subquery<String> knownAcronyms = query.subquery(String.class);
            Root<Exchange> exchange = knownAcronyms.from(Exchange.class);
            knownAcronyms.select(exchange.get("acronym"));
            return root.get("exchangeAcronym").in(knownAcronyms);
        };
    }

    /**
     * Filtrira listinge po tipu, pretrazi, exchange prefixu, rasponu cena i settlement date-u.
     */
    public static Specification<Listing> withFilters(
            ListingType type,
            String search,
            String exchangePrefix,
            BigDecimal priceMin,
            BigDecimal priceMax,
            LocalDate settlementDateFrom,
            LocalDate settlementDateTo) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("listingType"), type));

            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase() + "%";
                Predicate tickerMatch = cb.like(cb.lower(root.get("ticker")), pattern);
                Predicate nameMatch = cb.like(cb.lower(root.get("name")), pattern);
                predicates.add(cb.or(tickerMatch, nameMatch));
            }

            if (exchangePrefix != null && !exchangePrefix.isBlank()) {
                String prefixPattern = exchangePrefix.toLowerCase() + "%";
                predicates.add(cb.like(cb.lower(root.get("exchangeAcronym")), prefixPattern));
            }

            if (priceMin != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("price"), priceMin));
            }

            if (priceMax != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("price"), priceMax));
            }

            if (settlementDateFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("settlementDate"), settlementDateFrom));
            }

            if (settlementDateTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("settlementDate"), settlementDateTo));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
