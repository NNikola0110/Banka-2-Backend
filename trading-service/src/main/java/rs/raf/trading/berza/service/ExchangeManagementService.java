package rs.raf.trading.berza.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.berza.dto.ExchangeDto;
import rs.raf.trading.berza.model.Exchange;
import rs.raf.trading.berza.repository.ExchangeRepository;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * Servis za upravljanje berzama i proveru radnog vremena.
 *
 * Specifikacija: Celina 3 - Berza
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExchangeManagementService {

    private final ExchangeRepository exchangeRepository;

    /**
     * Proverava da li je berza trenutno otvorena.
     * Uzima u obzir vikende, praznike i radno vreme u lokalnoj vremenskoj zoni berze.
     *
     * <p>R2-1359 (§76): radno vreme berze ukljucuje i <b>pre-market</b> prozor.
     * Ako berza ima {@code preMarketOpenTime}, otvaranje pocinje od pre-marketa
     * (a ne tek od {@code openTime}) — trgovina u pre-marketu je deo radne sesije,
     * ne "zatvoreno". Pre fix-a je {@code isExchangeOpen} gledao samo
     * {@code [openTime, closeTime]}, pa je nalog u pre-marketu padao na
     * {@code isClosedOrAfterHours()==true} (spori fill) iako je berza po spec-u
     * radila. Post-market obrada (after-hours spori fill) ostaje na
     * {@link #isAfterHours} / {@link #isClosedOrAfterHours} — pre-market NE racuna
     * kao after-hours (to je produzena sesija, ne posle-zatvaranja prozor).
     */
    public boolean isExchangeOpen(String acronym) {
        Exchange exchange = exchangeRepository.findByAcronym(acronym)
                .orElseThrow(() -> new EntityNotFoundException("Exchange not found: " + acronym));
        return isExchangeOpen(exchange);
    }

    /**
     * R1-749 (N+1 fix): overload koji radi nad vec ucitanim {@link Exchange}
     * entitetom — bez dodatnog {@code findByAcronym} poziva. {@link #toDto} i
     * {@link #getAllExchanges} ga koriste da po berzi ne idu opet u bazu
     * (pre fix-a je {@code toDto} zvao {@code isExchangeOpen(acronym)} → nov
     * SELECT po svakoj berzi → N+1 nad {@code findByActiveTrue()} listom).
     */
    public boolean isExchangeOpen(Exchange exchange) {
        // R1-751: delistovana (neaktivna) berza nikad NIJE otvorena — bez obzira na
        // testMode ili radno vreme. Pre fix-a je inactive berza i dalje izvestavala
        // {@code isCurrentlyOpen=true} (test-mode ili u radnom vremenu), pa bi order
        // engine tretirao trgovinu kao da je berza ziva.
        if (!exchange.isActive()) {
            return false;
        }
        if (exchange.isTestMode()) {
            return true;
        }
        ZonedDateTime nowZ = nowInExchangeZone(exchange);
        if (isNonTradingDay(nowZ, exchange)) {
            return false;
        }
        LocalTime now = nowZ.toLocalTime();
        // §76: pre-market je deo radne sesije — efektivno otvaranje je
        // preMarketOpenTime ako je postavljen i raniji od openTime-a.
        LocalTime open = effectiveOpenTime(exchange);
        LocalTime close = exchange.getCloseTime();
        return isWithinTradingHours(now, open, close);
    }

    /**
     * Efektivno vreme otvaranja: ako berza ima {@code preMarketOpenTime} koji je
     * pre {@code openTime}, sesija pocinje od pre-marketa (§76). Inace regularni
     * {@code openTime}. Defanzivno: ako je pre-market posle open-a (nelogican
     * seed) ignorise se.
     */
    private static LocalTime effectiveOpenTime(Exchange exchange) {
        LocalTime open = exchange.getOpenTime();
        LocalTime pre = exchange.getPreMarketOpenTime();
        if (pre != null && open != null && pre.isBefore(open)) {
            return pre;
        }
        return open;
    }

    /**
     * Izdvojeno radi unit testova (mock trenutnog vremena u zoni berze).
     */
    ZonedDateTime nowInExchangeZone(Exchange exchange) {
        return ZonedDateTime.now(ZoneId.of(exchange.getTimeZone()));
    }

    /**
     * Proverava da li je dati datum vikend ili praznik za berzu.
     */
    private boolean isNonTradingDay(ZonedDateTime dateTime, Exchange exchange) {
        DayOfWeek dow = dateTime.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return true;
        }
        return exchange.getHolidays() != null && exchange.getHolidays().contains(dateTime.toLocalDate());
    }

    private static boolean isWithinTradingHours(LocalTime now, LocalTime open, LocalTime close) {
        if (!open.isAfter(close)) {
            return !now.isBefore(open) && !now.isAfter(close);
        }
        return !now.isBefore(open) || !now.isAfter(close);
    }

    /**
     * Vraca listu svih aktivnih berzi sa computed poljima.
     */
    public List<ExchangeDto> getAllExchanges() {
        return exchangeRepository.findByActiveTrue().stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Vraca detalje jedne berze po skracenici.
     */
    public ExchangeDto getByAcronym(String acronym) {
        Exchange exchange = exchangeRepository.findByAcronym(acronym)
                .orElseThrow(() -> new EntityNotFoundException("Exchange not found: " + acronym));
        return toDto(exchange);
    }

    /**
     * Ukljucuje/iskljucuje test mode za berzu.
     */
    @Transactional
    public void setTestMode(String acronym, boolean enabled) {
        Exchange exchange = exchangeRepository.findByAcronym(acronym)
                .orElseThrow(() -> new EntityNotFoundException("Exchange not found: " + acronym));
        exchange.setTestMode(enabled);
        exchangeRepository.save(exchange);
        log.info("Test mode for exchange {} set to {}", acronym, enabled);
    }

    /**
     * Proverava da li je berza u after-hours periodu (posle regularnog closeTime, do postMarketCloseTime).
     * Bez postMarketCloseTime nema after-hours prozora. Vikend i praznici: uvek false.
     * Test mode ne menja after-hours proveru (može biti i true i false po satu).
     *
     * Spec Celina 3 §404 trazi 4h prozor; za nase 6 berzi seedovani
     * postMarketCloseTime je vec close+4h (NYSE 16:00→20:00, LSE 16:30→20:30,
     * BELEX 15:00→19:00, ...). Za striktnu spec-only proveru bez oslonca na
     * postMarketCloseTime, koristi {@link #isWithinPostCloseWindow(String, int)}.
     */
    /** Default spec-pure post-close prozor (§404: 4h) kad berza nema seedovan postMarketCloseTime. */
    private static final int DEFAULT_POST_CLOSE_HOURS = 4;

    public boolean isAfterHours(String acronym) {
        Exchange exchange = exchangeRepository.findByAcronym(acronym)
                .orElseThrow(() -> new EntityNotFoundException("Exchange not found: " + acronym));
        LocalTime postEnd = exchange.getPostMarketCloseTime();
        if (postEnd == null) {
            // R1 452: berza bez seedovanog postMarketCloseTime — wire-uj spec-pure 4h
            // prozor (§404). Ranije se vracalo bezuslovno false (after-hours nikad
            // dostupan za takvu berzu).
            return isWithinPostCloseWindow(acronym, DEFAULT_POST_CLOSE_HOURS);
        }
        ZonedDateTime nowZ = nowInExchangeZone(exchange);
        if (isNonTradingDay(nowZ, exchange)) {
            return false;
        }
        LocalTime now = nowZ.toLocalTime();
        LocalTime close = exchange.getCloseTime();
        if (close == null) {
            return false;
        }
        // R1 449 (cross-midnight): ako post-market prozor prelazi ponoc
        // (postEnd <= close, npr. close=22:00 postEnd=02:00), stara grana
        // (`!postEnd.isAfter(close)` → false) ga je cinila NEDOSTIZNIM uvek.
        // Sada tretiramo wrap eksplicitno: posle close ILI pre postEnd (ujutru).
        if (!postEnd.isAfter(close)) {
            // wrap preko ponoci
            return now.isAfter(close) || now.isBefore(postEnd);
        }
        return now.isAfter(close) && now.isBefore(postEnd);
    }

    /**
     * R1-190 (§404): vraca {@code true} ako berza NIJE u regularnom radnom vremenu
     * — tj. ako je ZATVORENA (vikend, praznik, van radnih sati) ILI u after-hours
     * prozoru. Spec: "Obavestiti korisnika ako je berza zatvorena ... i ako je
     * berza u after-hours stanju ... Ako korisnik postavi Order u ovom slucaju,
     * njegovo ispunjavanje treba da se izvrsi sporije nego inace."
     *
     * <p>Order engine ({@code OrderServiceImpl.computeAfterHours}) koristi OVU
     * metodu da setuje {@code order.afterHours} → spori fill (dodatnih 30 min po
     * delu). Pre fix-a se oslanjalo samo na {@link #isAfterHours} (post-market
     * prozor), pa je potpuno zatvorena berza dobijala {@code afterHours=false} i
     * fill normalnom brzinom (bug).
     *
     * <p>Test mode čini berzu "otvorenom" ({@link #isExchangeOpen}) radi testiranja
     * van radnog vremena → {@code false} (normalan fill), sto je zeljeno ponasanje.
     */
    public boolean isClosedOrAfterHours(String acronym) {
        return !isExchangeOpen(acronym);
    }

    /**
     * Opciono.3 — generalizovana spec-konformna provera: trenutno vreme
     * u prozoru {@code (closeTime, closeTime + hours)} bez oslonca na
     * exchange.postMarketCloseTime. Spec Celina 3 §404 trazi 4h prozor.
     *
     * Vikend/praznici: uvek false. Test mode ne menja.
     *
     * @param acronym  exchange skracenica (NYSE, NASDAQ, ...)
     * @param hours    duzina prozora u satima (>= 1)
     */
    public boolean isWithinPostCloseWindow(String acronym, int hours) {
        if (hours <= 0) return false;
        Exchange exchange = exchangeRepository.findByAcronym(acronym)
                .orElseThrow(() -> new EntityNotFoundException("Exchange not found: " + acronym));
        LocalTime close = exchange.getCloseTime();
        if (close == null) return false;

        ZonedDateTime nowZ = nowInExchangeZone(exchange);
        if (isNonTradingDay(nowZ, exchange)) return false;

        LocalTime now = nowZ.toLocalTime();
        LocalTime windowEnd = close.plusHours(hours);

        // R1 449 (cross-midnight): ako close+hours wrap-uje preko ponoci
        // (npr. close=22:00, hours=4 → windowEnd=02:00), prozor je
        // (close, 24:00) U [00:00, windowEnd). Stara grana je vracala samo
        // now.isAfter(close) → propustala je rani-jutarnji deo (00:00-02:00).
        // (LocalTime.plusHours wrap-uje pa je windowEnd < close u tom slucaju.)
        if (!windowEnd.isAfter(close)) {
            return now.isAfter(close) || now.isBefore(windowEnd);
        }

        return now.isAfter(close) && now.isBefore(windowEnd);
    }

    /**
     * Racuna kada se berza sledeci put otvara (ISO 8601 string).
     * Uzima u obzir vikende i praznike — preskace neradne dane dok ne nadje prvi radni dan.
     */
    private String calculateNextOpenTime(Exchange exchange) {
        LocalTime openTime = exchange.getOpenTime();
        if (openTime == null) {
            return null;
        }

        ZoneId zone = ZoneId.of(exchange.getTimeZone());
        ZonedDateTime nowZ = nowInExchangeZone(exchange);
        LocalTime now = nowZ.toLocalTime();

        LocalDate candidate;
        if (!isNonTradingDay(nowZ, exchange) && now.isBefore(openTime)) {
            // Radni dan pre otvaranja — berza se otvara danas
            candidate = nowZ.toLocalDate();
        } else {
            // Kreni od sutra i nadji prvi radni dan koji nije praznik
            candidate = nowZ.toLocalDate().plusDays(1);
        }

        // Preskoci vikende i praznike (max 365 dana kao sigurnosni limit)
        int safetyCounter = 0;
        while (safetyCounter < 365) {
            DayOfWeek dow = candidate.getDayOfWeek();
            boolean isWeekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
            boolean isHoliday = exchange.getHolidays() != null && exchange.getHolidays().contains(candidate);
            if (!isWeekend && !isHoliday) {
                break;
            }
            candidate = candidate.plusDays(1);
            safetyCounter++;
        }

        ZonedDateTime nextOpen = candidate.atTime(openTime).atZone(zone);
        return nextOpen.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * Vraca praznike za berzu po skracenici.
     */
    public Set<LocalDate> getHolidays(String acronym) {
        Exchange exchange = exchangeRepository.findByAcronym(acronym)
                .orElseThrow(() -> new EntityNotFoundException("Exchange not found: " + acronym));
        return exchange.getHolidays();
    }

    /**
     * Postavlja praznike za berzu (zamenjuje postojece).
     */
    @Transactional
    public void setHolidays(String acronym, Set<LocalDate> holidays) {
        Exchange exchange = exchangeRepository.findByAcronym(acronym)
                .orElseThrow(() -> new EntityNotFoundException("Exchange not found: " + acronym));
        // R2 1419: berza ucitana sa null holidays kolekcijom (legacy red / direktan
        // konstruktor) bi bacila NPE na getHolidays().clear(). Inicijalizuj defanzivno.
        Set<LocalDate> target = ensureHolidaysInitialized(exchange);
        target.clear();
        if (holidays != null) {
            target.addAll(holidays);
        }
        exchangeRepository.save(exchange);
        log.info("Set {} holidays for exchange {}", target.size(), acronym);
    }

    /**
     * Dodaje praznik za berzu.
     */
    @Transactional
    public void addHoliday(String acronym, LocalDate date) {
        Exchange exchange = exchangeRepository.findByAcronym(acronym)
                .orElseThrow(() -> new EntityNotFoundException("Exchange not found: " + acronym));
        // R2 1419: null-safe — vidi setHolidays.
        ensureHolidaysInitialized(exchange).add(date);
        exchangeRepository.save(exchange);
        log.info("Added holiday {} for exchange {}", date, acronym);
    }

    /**
     * R2 1419: garantuje da {@code exchange.holidays} nije null pre mutacije
     * ({@code clear}/{@code add}). Vraca (eventualno novu) kolekciju.
     */
    private static Set<LocalDate> ensureHolidaysInitialized(Exchange exchange) {
        if (exchange.getHolidays() == null) {
            exchange.setHolidays(new java.util.HashSet<>());
        }
        return exchange.getHolidays();
    }

    /**
     * Uklanja praznik za berzu.
     */
    @Transactional
    public void removeHoliday(String acronym, LocalDate date) {
        Exchange exchange = exchangeRepository.findByAcronym(acronym)
                .orElseThrow(() -> new EntityNotFoundException("Exchange not found: " + acronym));
        // R2 1419: null-safe — bez inicijalizovane kolekcije remove je no-op.
        if (exchange.getHolidays() != null) {
            exchange.getHolidays().remove(date);
        }
        exchangeRepository.save(exchange);
        log.info("Removed holiday {} for exchange {}", date, acronym);
    }

    // ── Helper metode ───────────────────────────────────────────────────────────

    private ExchangeDto toDto(Exchange exchange) {
        // R1-749 (N+1 fix): koristi vec ucitan entitet umesto isExchangeOpen(acronym)
        // koji bi re-fetch-ovao istu berzu iz baze po svakom redu liste.
        boolean open = isExchangeOpen(exchange);
        String currentLocalTime;
        try {
            currentLocalTime = ZonedDateTime.now(ZoneId.of(exchange.getTimeZone()))
                    .toLocalTime().toString();
        } catch (Exception e) {
            currentLocalTime = LocalTime.now().toString();
        }

        return ExchangeDto.builder()
                .id(exchange.getId())
                .name(exchange.getName())
                .acronym(exchange.getAcronym())
                .micCode(exchange.getMicCode())
                .country(exchange.getCountry())
                .currency(exchange.getCurrency())
                .timeZone(exchange.getTimeZone())
                .openTime(exchange.getOpenTime() != null ? exchange.getOpenTime().toString() : null)
                .closeTime(exchange.getCloseTime() != null ? exchange.getCloseTime().toString() : null)
                .preMarketOpenTime(exchange.getPreMarketOpenTime() != null ? exchange.getPreMarketOpenTime().toString() : null)
                .postMarketCloseTime(exchange.getPostMarketCloseTime() != null ? exchange.getPostMarketCloseTime().toString() : null)
                .testMode(exchange.isTestMode())
                .active(exchange.isActive())
                .isCurrentlyOpen(open)
                .currentLocalTime(currentLocalTime)
                .nextOpenTime(open ? null : calculateNextOpenTime(exchange))
                .holidays(exchange.getHolidays())
                .build();
    }
}
