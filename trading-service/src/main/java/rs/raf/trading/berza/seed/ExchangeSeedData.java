package rs.raf.trading.berza.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import rs.raf.trading.berza.model.Exchange;
import rs.raf.trading.berza.repository.ExchangeRepository;

import java.time.LocalTime;
import java.util.List;

/**
 * Seed komponenta koja popunjava tabelu berzi pri pokretanju aplikacije.
 * Pokrece se samo ako je tabela prazna (count == 0).
 * Dodaje 7 berzi: NYSE, NASDAQ, CME, LSE, XETRA, BELEX, FOREX.
 *
 * <p>R1-191: FOREX listinzi ({@code listings.exchange_acronym='FOREX'} u
 * trading-seed.sql) referenciraju berzu sa akronimom "FOREX" koja RANIJE nije
 * postojala kao registrovana berza → {@code isExchangeOpen("FOREX")} je bacao
 * {@code RuntimeException("Exchange not found: FOREX")}, FOREX se nikad nije
 * mogao staviti u test-mode i nikad nije dobijao after-hours obradu. FOREX je
 * 24/5 trziste bez jedne fizicke berze — seedujemo sintetsku berzu koja je
 * efektivno otvorena 24h radnim danima (open 00:00, close 23:59, bez
 * post-marketa, vikend zatvoreno preko {@code isNonTradingDay}).
 *
 * <p><b>R1-755 (NAMERNA odluka — 24/7 demo simulacija):</b> svih 6 berzi se
 * seeduje sa {@code testMode=true}. Razlog: projekat NEMA pravi order-book ni
 * pristup zivim berzanskim feed-ovima, pa order engine radi nad
 * <em>simuliranim</em> cenama/volumenom (vidi {@code ListingServiceImpl}
 * sim-tick). {@code testMode=true} cini {@code isExchangeOpen} uvek true tako da
 * se demo/odbrana moze izvesti u bilo koje doba dana, van NYSE/BELEX radnog
 * vremena. Realno radno-vreme/after-hours logika je <b>implementirana i testirana</b>
 * ({@code isExchangeOpen}/{@code isAfterHours}/{@code isWithinPostCloseWindow} sa
 * pre/post-market i cross-midnight granicama) — supervizor je iskljucuje per-berza
 * preko {@code PATCH /exchanges/{acronym}/test-mode} kad zeli da testira pravo
 * radno vreme. Za produkciju (pravi feed) podrazumevana vrednost bi bila
 * {@code testMode=false}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExchangeSeedData implements ApplicationRunner {

    private final ExchangeRepository exchangeRepository;

    @Override
    public void run(ApplicationArguments args) {
        List<Exchange> exchanges = List.of(
                Exchange.builder()
                        .name("New York Stock Exchange").acronym("NYSE").micCode("XNYS")
                        .country("US").currency("USD").timeZone("America/New_York")
                        .openTime(LocalTime.of(9, 30)).closeTime(LocalTime.of(16, 0))
                        .preMarketOpenTime(LocalTime.of(4, 0)).postMarketCloseTime(LocalTime.of(20, 0))
                        .testMode(true)
                        .build(),
                Exchange.builder()
                        .name("NASDAQ").acronym("NASDAQ").micCode("XNAS")
                        .country("US").currency("USD").timeZone("America/New_York")
                        .openTime(LocalTime.of(9, 30)).closeTime(LocalTime.of(16, 0))
                        .preMarketOpenTime(LocalTime.of(4, 0)).postMarketCloseTime(LocalTime.of(20, 0))
                        .testMode(true)
                        .build(),
                Exchange.builder()
                        .name("Chicago Mercantile Exchange").acronym("CME").micCode("XCME")
                        .country("US").currency("USD").timeZone("America/Chicago")
                        .openTime(LocalTime.of(8, 30)).closeTime(LocalTime.of(15, 0))
                        .postMarketCloseTime(LocalTime.of(19, 0))
                        .testMode(true)
                        .build(),
                Exchange.builder()
                        .name("London Stock Exchange").acronym("LSE").micCode("XLON")
                        .country("GB").currency("GBP").timeZone("Europe/London")
                        .openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(16, 30))
                        // Opciono.3 — close+4h = 20:30, spec Celina 3 §404
                        .postMarketCloseTime(LocalTime.of(20, 30))
                        .testMode(true)
                        .build(),
                Exchange.builder()
                        .name("Deutsche Börse XETRA").acronym("XETRA").micCode("XETR")
                        .country("DE").currency("EUR").timeZone("Europe/Berlin")
                        .openTime(LocalTime.of(9, 0)).closeTime(LocalTime.of(17, 30))
                        .postMarketCloseTime(LocalTime.of(21, 30))
                        .testMode(true)
                        .build(),
                Exchange.builder()
                        .name("Belgrade Stock Exchange").acronym("BELEX").micCode("XBEL")
                        .country("RS").currency("RSD").timeZone("Europe/Belgrade")
                        .openTime(LocalTime.of(9, 0)).closeTime(LocalTime.of(15, 0))
                        .postMarketCloseTime(LocalTime.of(19, 0))
                        .testMode(true)
                        .build(),
                // R1-191: sintetska FOREX berza (24/5 trziste). Listinzi sa
                // exchange_acronym='FOREX' sad razresavaju validnu berzu —
                // isExchangeOpen radi, FOREX se moze staviti u test-mode, i
                // dobija after-hours/zatvoreno tretman kao i ostale berze.
                // Open 00:00 / Close 23:59:59 → efektivno 24h radnim danima;
                // vikend zatvoreno preko isNonTradingDay (SUBOTA/NEDELJA).
                Exchange.builder()
                        .name("Foreign Exchange Market").acronym("FOREX").micCode("XOFF")
                        .country("US").currency("USD").timeZone("America/New_York")
                        .openTime(LocalTime.of(0, 0)).closeTime(LocalTime.of(23, 59, 59))
                        .testMode(true)
                        .build()
        );

        // R1-451: per-acronym idempotentan seed (umesto count()==0 koji je u
        // multi-replica deploy-u race-bilan — obe replike vide count==0 i obe
        // ubace ceo set → duplikati / DataIntegrityViolation). Ubacujemo SAMO
        // berze cijih acronyma jos nema; pravu garanciju daje UNIQUE(acronym) —
        // ako dve replike istovremeno krenu da seed-uju isti acronym, druga dobije
        // DataIntegrityViolation koji svesno gutamo (red vec postoji).
        int seeded = 0;
        for (Exchange exchange : exchanges) {
            if (exchangeRepository.findByAcronym(exchange.getAcronym()).isPresent()) {
                continue;
            }
            try {
                exchangeRepository.save(exchange);
                seeded++;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Druga replika je ubacila isti acronym izmedju find-a i save-a — OK.
                log.debug("Exchange {} vec postoji (concurrent seed), preskacem.", exchange.getAcronym());
            }
        }
        log.info("Exchange seed: ubaceno {} novih berzi (od {} definisanih).", seeded, exchanges.size());
    }
}
