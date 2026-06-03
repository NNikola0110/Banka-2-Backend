package rs.raf.trading.berza.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.trading.berza.model.Exchange;
import rs.raf.trading.berza.repository.ExchangeRepository;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeManagementServiceTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Mock
    private ExchangeRepository exchangeRepository;

    private ExchangeManagementService service;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new ExchangeManagementService(exchangeRepository));
    }

    private Exchange nyseNormalHours() {
        return Exchange.builder()
                .id(1L)
                .name("New York Stock Exchange")
                .acronym("NYSE")
                .micCode("XNYS")
                .country("US")
                .currency("USD")
                .timeZone("America/New_York")
                .openTime(LocalTime.of(9, 30))
                .closeTime(LocalTime.of(16, 0))
                .testMode(false)
                .active(true)
                .build();
    }

    @Test
    void isExchangeOpen_whenExchangeMissing_throws() {
        when(exchangeRepository.findByAcronym("UNKNOWN")).thenReturn(Optional.empty());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.isExchangeOpen("UNKNOWN"));

        assertTrue(ex.getMessage().contains("Exchange not found"));
    }

    @Test
    void isExchangeOpen_whenTestMode_returnsTrueRegardlessOfTime() {
        Exchange ex = nyseNormalHours();
        ex.setTestMode(true);
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));

        assertTrue(service.isExchangeOpen("NYSE"));
    }

    @Test
    void isExchangeOpen_onSaturday_returnsFalse() {
        Exchange ex = nyseNormalHours();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isExchangeOpen("NYSE"));
    }

    @Test
    void isExchangeOpen_onSunday_returnsFalse() {
        Exchange ex = nyseNormalHours();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 29, 12, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isExchangeOpen("NYSE"));
    }

    @Test
    void isExchangeOpen_weekdayWithinOpenClose_returnsTrue() {
        Exchange ex = nyseNormalHours();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 10, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isExchangeOpen("NYSE"));
    }

    @Test
    void isExchangeOpen_weekdayAtOpenBoundary_returnsTrue() {
        Exchange ex = nyseNormalHours();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 9, 30, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isExchangeOpen("NYSE"));
    }

    @Test
    void isExchangeOpen_weekdayAtCloseBoundary_returnsTrue() {
        Exchange ex = nyseNormalHours();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 16, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isExchangeOpen("NYSE"));
    }

    @Test
    void isExchangeOpen_weekdayBeforeOpen_returnsFalse() {
        Exchange ex = nyseNormalHours();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 9, 29, 59, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isExchangeOpen("NYSE"));
    }

    @Test
    void isExchangeOpen_weekdayAfterClose_returnsFalse() {
        Exchange ex = nyseNormalHours();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 16, 0, 0, 1, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isExchangeOpen("NYSE"));
    }

    @Test
    void isExchangeOpen_overnightSession_midnight_returnsTrue() {
        Exchange ex = Exchange.builder()
                .id(2L)
                .name("Overnight")
                .acronym("ON")
                .micCode("XON")
                .country("US")
                .currency("USD")
                .timeZone("America/New_York")
                .openTime(LocalTime.of(22, 0))
                .closeTime(LocalTime.of(6, 0))
                .testMode(false)
                .active(true)
                .build();
        when(exchangeRepository.findByAcronym("ON")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 23, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isExchangeOpen("ON"));
    }

    @Test
    void isExchangeOpen_overnightSession_earlyMorning_returnsTrue() {
        Exchange ex = Exchange.builder()
                .id(2L)
                .name("Overnight")
                .acronym("ON")
                .micCode("XON")
                .country("US")
                .currency("USD")
                .timeZone("America/New_York")
                .openTime(LocalTime.of(22, 0))
                .closeTime(LocalTime.of(6, 0))
                .testMode(false)
                .active(true)
                .build();
        when(exchangeRepository.findByAcronym("ON")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 5, 30, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isExchangeOpen("ON"));
    }

    @Test
    void isExchangeOpen_overnightSession_midday_returnsFalse() {
        Exchange ex = Exchange.builder()
                .id(2L)
                .name("Overnight")
                .acronym("ON")
                .micCode("XON")
                .country("US")
                .currency("USD")
                .timeZone("America/New_York")
                .openTime(LocalTime.of(22, 0))
                .closeTime(LocalTime.of(6, 0))
                .testMode(false)
                .active(true)
                .build();
        when(exchangeRepository.findByAcronym("ON")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 12, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isExchangeOpen("ON"));
    }

    private Exchange nyseWithPostMarket() {
        return Exchange.builder()
                .id(1L)
                .name("New York Stock Exchange")
                .acronym("NYSE")
                .micCode("XNYS")
                .country("US")
                .currency("USD")
                .timeZone("America/New_York")
                .openTime(LocalTime.of(9, 30))
                .closeTime(LocalTime.of(16, 0))
                .postMarketCloseTime(LocalTime.of(20, 0))
                .testMode(false)
                .active(true)
                .build();
    }

    @Test
    void isAfterHours_whenExchangeMissing_throws() {
        when(exchangeRepository.findByAcronym("UNKNOWN")).thenReturn(Optional.empty());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.isAfterHours("UNKNOWN"));

        assertTrue(ex.getMessage().contains("Exchange not found"));
    }

    @Test
    void isAfterHours_whenNoPostMarketCloseTime_duringRegularSession_returnsFalse() {
        // R1 452: bez postMarketCloseTime isAfterHours sada wire-uje spec-pure 4h
        // prozor (NE vraca bezuslovno false). U regularnoj sesiji (pre close) je false.
        Exchange ex = nyseNormalHours();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 12, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isAfterHours("NYSE"));
    }

    @Test
    void isAfterHours_whenNoPostMarketCloseTime_withinDefault4hWindow_returnsTrue() {
        // R1 452 (KLJUCNI): berza bez seedovanog postMarketCloseTime — u 17:00
        // (1h posle close 16:00) je sada U after-hours prozoru (spec-pure 4h).
        // Pre fix-a je isAfterHours vracao bezuslovno false → after-hours nedostizan.
        Exchange ex = nyseNormalHours();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 17, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isAfterHours("NYSE"));
    }

    @Test
    void isAfterHours_crossMidnight_earlyMorningWithinWindow_returnsTrue() {
        // R1 449 (cross-midnight): post-market prozor prelazi ponoc
        // (close=22:00, postMarketCloseTime=02:00). U 01:00 je U prozoru.
        // Pre fix-a je grana `!postEnd.isAfter(close)` cinila prozor NEDOSTIZNIM (uvek false).
        Exchange ex = Exchange.builder()
                .id(9L).name("Late").acronym("LATE").micCode("XLAT")
                .country("US").currency("USD").timeZone("America/New_York")
                .openTime(LocalTime.of(14, 0))
                .closeTime(LocalTime.of(22, 0))
                .postMarketCloseTime(LocalTime.of(2, 0)) // 02:00 sledeci dan (wrap)
                .testMode(false).active(true).build();
        when(exchangeRepository.findByAcronym("LATE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 31, 1, 0, 0, 0, NY)) // utorak 01:00
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isAfterHours("LATE"));
    }

    @Test
    void isAfterHours_crossMidnight_pastWindowEnd_returnsFalse() {
        // R1 449: u 03:00 (posle 02:00 windowEnd) — van prozora.
        Exchange ex = Exchange.builder()
                .id(9L).name("Late").acronym("LATE").micCode("XLAT")
                .country("US").currency("USD").timeZone("America/New_York")
                .openTime(LocalTime.of(14, 0))
                .closeTime(LocalTime.of(22, 0))
                .postMarketCloseTime(LocalTime.of(2, 0))
                .testMode(false).active(true).build();
        when(exchangeRepository.findByAcronym("LATE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 31, 3, 0, 0, 0, NY)) // 03:00
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isAfterHours("LATE"));
    }

    @Test
    void isAfterHours_whenTestMode_stillTrueInAfterHoursWindow() {
        Exchange ex = nyseWithPostMarket();
        ex.setTestMode(true);
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 18, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isAfterHours("NYSE"));
    }

    @Test
    void isAfterHours_whenTestMode_stillFalseOutsideAfterHoursWindow() {
        Exchange ex = nyseWithPostMarket();
        ex.setTestMode(true);
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 10, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isAfterHours("NYSE"));
    }

    @Test
    void isAfterHours_weekend_returnsFalseEvenIfTimeInWindow() {
        Exchange ex = nyseWithPostMarket();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 28, 18, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isAfterHours("NYSE"));
    }

    @Test
    void isAfterHours_duringRegularSession_returnsFalse() {
        Exchange ex = nyseWithPostMarket();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 10, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isAfterHours("NYSE"));
    }

    @Test
    void isAfterHours_atRegularClose_returnsFalse() {
        Exchange ex = nyseWithPostMarket();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 16, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isAfterHours("NYSE"));
    }

    @Test
    void isAfterHours_afterCloseBeforePostEnd_returnsTrue() {
        Exchange ex = nyseWithPostMarket();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 18, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isAfterHours("NYSE"));
    }

    @Test
    void isAfterHours_immediatelyAfterClose_returnsTrue() {
        Exchange ex = nyseWithPostMarket();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 16, 0, 0, 1, NY))
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isAfterHours("NYSE"));
    }

    @Test
    void isAfterHours_atPostMarketClose_returnsFalse() {
        Exchange ex = nyseWithPostMarket();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 20, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isAfterHours("NYSE"));
    }

    @Test
    void isAfterHours_afterPostMarketClose_returnsFalse() {
        Exchange ex = nyseWithPostMarket();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 20, 0, 0, 1, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isAfterHours("NYSE"));
    }

    @Test
    void isAfterHours_whenPostMarketNotAfterClose_returnsFalse() {
        Exchange ex = Exchange.builder()
                .id(3L)
                .name("Bad data")
                .acronym("BAD")
                .micCode("XBAD")
                .country("US")
                .currency("USD")
                .timeZone("America/New_York")
                .openTime(LocalTime.of(9, 30))
                .closeTime(LocalTime.of(16, 0))
                .postMarketCloseTime(LocalTime.of(15, 0))
                .testMode(false)
                .active(true)
                .build();
        when(exchangeRepository.findByAcronym("BAD")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 15, 30, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isAfterHours("BAD"));
    }

    // -------------------------------------------------------------------
    // R1-190 (§404) — isClosedOrAfterHours (spori fill kad je zatvorena ILI after-hours)
    // -------------------------------------------------------------------

    @Test
    void isClosedOrAfterHours_duringRegularSession_returnsFalse() {
        Exchange ex = nyseWithPostMarket();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 10, 0, 0, 0, NY)) // ponedeljak, regularno
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isClosedOrAfterHours("NYSE"));
    }

    @Test
    void isClosedOrAfterHours_inAfterHoursWindow_returnsTrue() {
        Exchange ex = nyseWithPostMarket();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 18, 0, 0, 0, NY)) // posle close, pre post-close
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isClosedOrAfterHours("NYSE"));
    }

    @Test
    void isClosedOrAfterHours_fullyClosedDeepNight_returnsTrue() {
        // Kljucni R1-190 slucaj: vise od 4h posle zatvaranja (post-market prošao) →
        // berza je ZATVORENA → spori fill (pre fix-a je bilo false → normalan fill).
        Exchange ex = nyseWithPostMarket(); // post-market do 20:00
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 23, 0, 0, 0, NY)) // 23:00, posle post-marketa
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isAfterHours("NYSE")); // van after-hours prozora
        assertTrue(service.isClosedOrAfterHours("NYSE")); // ali jeste zatvorena → spori fill
    }

    @Test
    void isClosedOrAfterHours_weekend_returnsTrue() {
        Exchange ex = nyseWithPostMarket();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, NY)) // subota podne
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isClosedOrAfterHours("NYSE"));
    }

    @Test
    void isClosedOrAfterHours_testMode_returnsFalse() {
        // Test mode čini berzu "otvorenom" radi testiranja → normalan fill.
        Exchange ex = nyseWithPostMarket();
        ex.setTestMode(true);
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));

        assertFalse(service.isClosedOrAfterHours("NYSE"));
    }

    // -------------------------------------------------------------------
    // Opciono.3 — isWithinPostCloseWindow (spec Celina 3 §404 4h prozor)
    // -------------------------------------------------------------------

    @Test
    void isWithinPostCloseWindow_4hWindow_atOnePastClose_returnsTrue() {
        Exchange ex = nyseNormalHours(); // close 16:00, bez postMarketCloseTime
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 17, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isWithinPostCloseWindow("NYSE", 4));
    }

    @Test
    void isWithinPostCloseWindow_4hWindow_atFourHoursAfterClose_returnsFalse() {
        Exchange ex = nyseNormalHours(); // close 16:00
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 20, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isWithinPostCloseWindow("NYSE", 4));
    }

    @Test
    void isWithinPostCloseWindow_4hWindow_atRegularSession_returnsFalse() {
        Exchange ex = nyseNormalHours();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 12, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isWithinPostCloseWindow("NYSE", 4));
    }

    @Test
    void isWithinPostCloseWindow_4hWindow_onWeekend_returnsFalse() {
        Exchange ex = nyseNormalHours();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 28, 18, 0, 0, 0, NY)) // Saturday
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isWithinPostCloseWindow("NYSE", 4));
    }

    @Test
    void isWithinPostCloseWindow_zeroHours_alwaysFalse() {
        // Bez stub-a — kratko-spojeni return za hours <= 0 ne dotice repo
        assertFalse(service.isWithinPostCloseWindow("NYSE", 0));
        assertFalse(service.isWithinPostCloseWindow("NYSE", -1));
    }

    @Test
    void isWithinPostCloseWindow_2hWindow_atTwoPastClose_returnsFalse() {
        Exchange ex = nyseNormalHours(); // close 16:00
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 18, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        // 2h prozor (16:00-18:00) — u 18:00 je vec van prozora
        assertFalse(service.isWithinPostCloseWindow("NYSE", 2));
    }

    @Test
    void isWithinPostCloseWindow_2hWindow_atOneHourPastClose_returnsTrue() {
        Exchange ex = nyseNormalHours(); // close 16:00
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 17, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isWithinPostCloseWindow("NYSE", 2));
    }

    // -------------------------------------------------------------------
    // R2-1359 (§76) — pre-market prozor ulazi u isExchangeOpen
    // -------------------------------------------------------------------

    private Exchange nyseWithPreMarket() {
        return Exchange.builder()
                .id(1L)
                .name("New York Stock Exchange")
                .acronym("NYSE")
                .micCode("XNYS")
                .country("US")
                .currency("USD")
                .timeZone("America/New_York")
                .preMarketOpenTime(LocalTime.of(4, 0))
                .openTime(LocalTime.of(9, 30))
                .closeTime(LocalTime.of(16, 0))
                .postMarketCloseTime(LocalTime.of(20, 0))
                .testMode(false)
                .active(true)
                .build();
    }

    @Test
    void isExchangeOpen_duringPreMarket_returnsTrue() {
        // R2-1359: u 5:00 (pre-market 4:00-9:30) berza je po §76 OTVORENA.
        // Pre fix-a je gledala samo [9:30, 16:00] → false → spori fill.
        Exchange ex = nyseWithPreMarket();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 5, 0, 0, 0, NY)) // ponedeljak, pre-market
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isExchangeOpen("NYSE"));
        // pre-market NIJE after-hours (to je produzena sesija, ne posle-zatvaranja)
        assertFalse(service.isClosedOrAfterHours("NYSE"));
    }

    @Test
    void isExchangeOpen_atPreMarketOpenBoundary_returnsTrue() {
        Exchange ex = nyseWithPreMarket();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 4, 0, 0, 0, NY)) // tacno 4:00
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isExchangeOpen("NYSE"));
    }

    @Test
    void isExchangeOpen_beforePreMarketOpen_returnsFalse() {
        Exchange ex = nyseWithPreMarket();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 3, 59, 59, 0, NY)) // pre pre-marketa
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isExchangeOpen("NYSE"));
        assertTrue(service.isClosedOrAfterHours("NYSE")); // zatvorena → spori fill
    }

    @Test
    void isExchangeOpen_noPreMarket_beforeRegularOpen_returnsFalseUnchanged() {
        // Regresija: berza BEZ pre-marketa (nyseNormalHours) zadrzava staro
        // ponasanje — pre 9:30 je zatvorena.
        Exchange ex = nyseNormalHours();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 9, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isExchangeOpen("NYSE"));
    }

    @Test
    void isExchangeOpen_preMarket_weekendStillClosed() {
        Exchange ex = nyseWithPreMarket();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 28, 5, 0, 0, 0, NY)) // subota, pre-market sat
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isExchangeOpen("NYSE"));
    }

    // -------------------------------------------------------------------
    // R2 1419 — setHolidays / addHoliday / removeHoliday null-holidays NPE guard
    // -------------------------------------------------------------------

    @Test
    void setHolidays_whenHolidaysNull_doesNotNpeAndInitializes() {
        Exchange ex = nyseNormalHours();
        ex.setHolidays(null); // simulira legacy red / direktan konstruktor
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));

        java.util.Set<java.time.LocalDate> hol =
                java.util.Set.of(java.time.LocalDate.of(2026, 1, 1));
        service.setHolidays("NYSE", hol); // ne sme NPE

        org.junit.jupiter.api.Assertions.assertNotNull(ex.getHolidays());
        assertTrue(ex.getHolidays().contains(java.time.LocalDate.of(2026, 1, 1)));
        Mockito.verify(exchangeRepository).save(ex);
    }

    @Test
    void setHolidays_nullHolidaysArg_clearsToEmptyNoNpe() {
        Exchange ex = nyseNormalHours();
        ex.setHolidays(new java.util.HashSet<>(java.util.Set.of(java.time.LocalDate.of(2026, 5, 1))));
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));

        service.setHolidays("NYSE", null); // null arg ne sme NPE

        assertTrue(ex.getHolidays().isEmpty());
    }

    @Test
    void addHoliday_whenHolidaysNull_doesNotNpeAndAdds() {
        Exchange ex = nyseNormalHours();
        ex.setHolidays(null);
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));

        service.addHoliday("NYSE", java.time.LocalDate.of(2026, 12, 25)); // ne sme NPE

        org.junit.jupiter.api.Assertions.assertNotNull(ex.getHolidays());
        assertTrue(ex.getHolidays().contains(java.time.LocalDate.of(2026, 12, 25)));
    }

    @Test
    void removeHoliday_whenHolidaysNull_doesNotNpe() {
        Exchange ex = nyseNormalHours();
        ex.setHolidays(null);
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));

        // ne sme NPE; tiho no-op
        service.removeHoliday("NYSE", java.time.LocalDate.of(2026, 1, 1));

        Mockito.verify(exchangeRepository).save(ex);
    }

    // ── R1-749: getAllExchanges N+1 — toDto koristi vec ucitan entitet ───────────

    @Test
    void getAllExchanges_doesNotRefetchPerExchange_R1_749() {
        Exchange a = nyseNormalHours();
        a.setTestMode(true); // testMode → isExchangeOpen true bez vremenske grane
        Exchange b = Exchange.builder()
                .id(2L).name("Belgrade").acronym("BELEX").micCode("XBEL")
                .country("RS").currency("RSD").timeZone("Europe/Belgrade")
                .openTime(LocalTime.of(9, 0)).closeTime(LocalTime.of(15, 0))
                .testMode(true).active(true)
                .build();
        when(exchangeRepository.findByActiveTrue())
                .thenReturn(java.util.List.of(a, b));

        var result = service.getAllExchanges();

        org.junit.jupiter.api.Assertions.assertEquals(2, result.size());
        assertTrue(result.get(0).isCurrentlyOpen());
        assertTrue(result.get(1).isCurrentlyOpen());
        // N+1 fix: toDto NE sme zvati findByAcronym po berzi (open se racuna iz vec
        // ucitanog entiteta). Pre fix-a bi bilo 2 dodatna findByAcronym poziva.
        Mockito.verify(exchangeRepository, Mockito.never())
                .findByAcronym(org.mockito.ArgumentMatchers.anyString());
    }

    // ── R1-751: delistovana (neaktivna) berza nikad nije otvorena ────────────────

    @Test
    void isExchangeOpen_inactiveExchange_returnsFalse_evenInTestMode_R1_751() {
        Exchange ex = nyseNormalHours();
        ex.setActive(false);
        ex.setTestMode(true); // i pored testMode-a, inactive → zatvorena
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));

        assertFalse(service.isExchangeOpen("NYSE"));
    }

    @Test
    void isExchangeOpen_inactiveExchange_returnsFalse_evenWithinTradingHours_R1_751() {
        Exchange ex = nyseNormalHours();
        ex.setActive(false);
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        // NB: inactive guard short-circuit-uje PRE vremenske provere, pa
        // nowInExchangeZone nije ni dosegnut (stub bi bio UnnecessaryStubbing) —
        // to dokazuje da active check ima prednost nad radnim vremenom.

        assertFalse(service.isExchangeOpen("NYSE"));
        Mockito.verify(service, Mockito.never()).nowInExchangeZone(any());
    }

    // -------------------------------------------------------------------
    // OT-1079 (TEST-tr-tax-actuary-exchange-1) — isExchangeOpen holiday grana
    // (isNonTradingDay drugi uslov: praznik na RADNI dan). Postojeci testovi
    // pokrivaju samo vikend; praznik-na-radni-dan grana nije bila pinned.
    // -------------------------------------------------------------------

    @Test
    void isExchangeOpen_onHolidayWeekday_returnsFalse_OT1079() {
        // 2026-01-01 (Nova godina) je CETVRTAK — radni dan, ali praznik.
        // Bez holiday grane berza bi (u radnom vremenu 10:00) izvestila otvoreno;
        // sa praznikom u getHolidays() → isNonTradingDay==true → zatvorena.
        Exchange ex = nyseNormalHours();
        ex.setHolidays(new java.util.HashSet<>(
                java.util.Set.of(java.time.LocalDate.of(2026, 1, 1))));
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, NY)) // cetvrtak, radno vreme, ALI praznik
                .when(service).nowInExchangeZone(any());

        assertFalse(service.isExchangeOpen("NYSE"));
    }

    @Test
    void isExchangeOpen_weekdayNotInHolidaySet_returnsTrue_OT1079() {
        // Kontrola: isti radni dan koji NIJE u holiday setu → otvorena.
        // Dokazuje da je upravo holiday-clanstvo to sto zatvara berzu (ne sam datum).
        Exchange ex = nyseNormalHours();
        ex.setHolidays(new java.util.HashSet<>(
                java.util.Set.of(java.time.LocalDate.of(2026, 1, 1))));
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 1, 2, 10, 0, 0, 0, NY)) // petak 2.1. NIJE praznik
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isExchangeOpen("NYSE"));
    }

    @Test
    void isClosedOrAfterHours_onHolidayWeekday_returnsTrue_OT1079() {
        // Spec Celina 3 §404: na praznik je berza zatvorena → spori fill (afterHours).
        Exchange ex = nyseNormalHours();
        ex.setHolidays(new java.util.HashSet<>(
                java.util.Set.of(java.time.LocalDate.of(2026, 1, 1))));
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, NY))
                .when(service).nowInExchangeZone(any());

        assertTrue(service.isClosedOrAfterHours("NYSE"));
    }

    // -------------------------------------------------------------------
    // OT-1088 (TEST-tr-tax-actuary-exchange-1) — calculateNextOpenTime preko
    // praznika/vikenda. Metoda je private; dohvata se preko toDto (getByAcronym/
    // getAllExchanges) koji racuna nextOpenTime SAMO kad je berza zatvorena.
    // Pre ovog batch-a nextOpenTime polje nije bilo asertovano nijednim testom.
    // -------------------------------------------------------------------

    @Test
    void getByAcronym_nextOpenTime_skipsWeekend_OT1088() {
        // Petak posle zatvaranja (17:00) → sledece otvaranje je PONEDELJAK 09:30
        // (preskoci subotu+nedelju). 2026-03-27 je petak.
        Exchange ex = nyseNormalHours();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 27, 17, 0, 0, 0, NY)) // petak 17:00, zatvoreno
                .when(service).nowInExchangeZone(any());

        var dto = service.getByAcronym("NYSE");

        assertFalse(dto.isCurrentlyOpen());
        org.junit.jupiter.api.Assertions.assertNotNull(dto.getNextOpenTime());
        // Sledece otvaranje je ponedeljak 2026-03-30, NE subota/nedelja.
        assertTrue(dto.getNextOpenTime().startsWith("2026-03-30T09:30"),
                "Ocekivano ponedeljak 09:30, dobijeno: " + dto.getNextOpenTime());
    }

    @Test
    void getByAcronym_nextOpenTime_skipsHoliday_OT1088() {
        // Sreda posle zatvaranja; cetvrtak je praznik → sledece otvaranje je PETAK 09:30.
        // 2026-12-23 sreda, 2026-12-24 cetvrtak (praznik), 2026-12-25 petak.
        Exchange ex = nyseNormalHours();
        ex.setHolidays(new java.util.HashSet<>(
                java.util.Set.of(java.time.LocalDate.of(2026, 12, 24))));
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 12, 23, 17, 0, 0, 0, NY)) // sreda 17:00, zatvoreno
                .when(service).nowInExchangeZone(any());

        var dto = service.getByAcronym("NYSE");

        assertFalse(dto.isCurrentlyOpen());
        // Cetvrtak 24.12 je praznik → preskace se → petak 25.12 09:30.
        assertTrue(dto.getNextOpenTime().startsWith("2026-12-25T09:30"),
                "Ocekivano petak 09:30 (preskocen cetvrtak-praznik), dobijeno: " + dto.getNextOpenTime());
    }

    @Test
    void getByAcronym_nextOpenTime_sameDayBeforeOpen_OT1088() {
        // Radni dan PRE otvaranja (08:00 < 09:30) → otvara se DANAS, ne sutra.
        Exchange ex = nyseNormalHours();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 8, 0, 0, 0, NY)) // ponedeljak 08:00, jos zatvoreno
                .when(service).nowInExchangeZone(any());

        var dto = service.getByAcronym("NYSE");

        assertFalse(dto.isCurrentlyOpen());
        assertTrue(dto.getNextOpenTime().startsWith("2026-03-30T09:30"),
                "Ocekivano isti dan 09:30, dobijeno: " + dto.getNextOpenTime());
    }

    @Test
    void getByAcronym_nextOpenTime_holidayChainSkipsWeekendAndHoliday_OT1088() {
        // Petak posle zatvaranja; ponedeljak je praznik → preskoci subotu+nedelju+ponedeljak-praznik
        // → otvaranje je UTORAK 09:30. 2026-03-27 petak, 30.03 ponedeljak (praznik), 31.03 utorak.
        Exchange ex = nyseNormalHours();
        ex.setHolidays(new java.util.HashSet<>(
                java.util.Set.of(java.time.LocalDate.of(2026, 3, 30))));
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 27, 17, 0, 0, 0, NY)) // petak 17:00
                .when(service).nowInExchangeZone(any());

        var dto = service.getByAcronym("NYSE");

        assertFalse(dto.isCurrentlyOpen());
        assertTrue(dto.getNextOpenTime().startsWith("2026-03-31T09:30"),
                "Ocekivano utorak 09:30 (preskocen vikend + ponedeljak-praznik), dobijeno: "
                        + dto.getNextOpenTime());
    }

    @Test
    void getByAcronym_openExchange_nextOpenTimeNull_OT1088() {
        // Kad je berza OTVORENA, nextOpenTime je null (nema "sledeceg" otvaranja).
        Exchange ex = nyseNormalHours();
        when(exchangeRepository.findByAcronym("NYSE")).thenReturn(Optional.of(ex));
        doReturn(ZonedDateTime.of(2026, 3, 30, 10, 0, 0, 0, NY)) // ponedeljak, otvoreno
                .when(service).nowInExchangeZone(any());

        var dto = service.getByAcronym("NYSE");

        assertTrue(dto.isCurrentlyOpen());
        org.junit.jupiter.api.Assertions.assertNull(dto.getNextOpenTime());
    }
}
