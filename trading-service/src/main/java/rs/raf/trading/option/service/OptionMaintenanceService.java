package rs.raf.trading.option.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.option.model.Option;
import rs.raf.trading.option.model.OptionType;
import rs.raf.trading.option.repository.OptionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * P2-money-tx-1 (R3 1587): nosilac stvarnih transakcionih jedinica dnevnog
 * odrzavanja opcija.
 *
 * <p><b>Zasto izdvojeno iz {@code OptionScheduler}:</b> ranije su
 * {@code cleanupExpiredOptions}/{@code recalculatePrices} bili
 * {@code @Transactional protected} metode pozivane SELF-INVOCATION-om iz
 * {@code dailyOptionMaintenance()} u istoj klasi. Spring AOP {@code @Transactional}
 * radi samo na proxy-presretnutim pozivima (public metoda zvana SPOLJA preko
 * bean reference) — self-invocation i {@code protected} vidljivost oboje
 * zaobilaze proxy, pa je {@code @Transactional} bio NO-OP. Posledica: {@code saveAll}
 * od 100+ redova u {@code recalculatePrices} se izvrsavao BEZ tx granice —
 * delimican update bez rollback-a na pad u sredini (svaki red flush-ovan
 * pojedinacno bez atomicnosti).
 *
 * <p>Sad su to public metode na zasebnom {@code @Service} bean-u; scheduler ih
 * zove kroz proxy → {@code @Transactional} se ZAISTA primenjuje (atomican
 * commit/rollback po jedinici odrzavanja).
 */
@Service
@RequiredArgsConstructor
public class OptionMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(OptionMaintenanceService.class);

    private final OptionRepository optionRepository;
    private final OptionGeneratorService optionGeneratorService;
    private final BlackScholesService blackScholesService;

    /** Brise sve opcije kojima je istekao settlement datum (atomicno). */
    @Transactional
    public void cleanupExpiredOptions() {
        List<Option> expired = optionRepository.findBySettlementDateBefore(LocalDate.now());
        int count = expired.size();
        if (count > 0) {
            optionRepository.deleteBySettlementDateBefore(LocalDate.now());
            log.info("Obrisano {} isteklih opcija", count);
        } else {
            log.info("Nema isteklih opcija za brisanje");
        }
    }

    /** Generise nove opcije za settlement datume koji jos ne postoje. */
    @Transactional
    public void generateNewOptions() {
        log.info("Generisanje novih opcija...");
        optionGeneratorService.generateAllOptions();
    }

    /**
     * Rekalkulise cene svih postojecih opcija koristeci Black-Scholes (atomicno —
     * {@code saveAll} commituje/rollback-uje kao celina, vise NE parcijalno).
     *
     * <p>P2-state-machine-1 (R1 458 / R3 1615):
     * <ul>
     *   <li><b>maintenanceMargin se OSVEZAVA</b> zajedno sa cenom. Margin zavisi
     *       od cene osnovne akcije ({@code ContractSize × 50% × StockPrice}); kad
     *       se cena promeni a margin ostane stari, prikazana margina je stale
     *       (do ~2× pogresna na volatilnoj akciji). Sada se racuna istim
     *       {@link OptionGeneratorService} formulom uz STVARNI contractSize opcije.</li>
     *   <li><b>{@code saveAll} prima SAMO izmenjene opcije</b> (ne ceo
     *       {@code findAll}). Preskocene (null-price / istekle) se ne dirају i ne
     *       persistuju nepotrebno — manje write-amplifikacije i nema laznih
     *       version-bump-ova na netaknutim redovima.</li>
     * </ul>
     */
    @Transactional
    public void recalculatePrices() {
        // OT-896: join-fetch stockListing-a u jednom upitu — petlja ispod cita
        // option.getStockListing().getPrice() po opciji, sto bi sa obicnim
        // findAll() (LAZY @ManyToOne) okinulo N+1 (1 + N SELECT-ova).
        List<Option> allOptions = optionRepository.findAllWithStockListing();
        LocalDate today = LocalDate.now();
        List<Option> changed = new java.util.ArrayList<>();

        for (Option option : allOptions) {
            BigDecimal stockPrice = option.getStockListing().getPrice();
            if (stockPrice == null) continue;

            long daysToExpiry = ChronoUnit.DAYS.between(today, option.getSettlementDate());
            if (daysToExpiry <= 0) continue;

            double T = daysToExpiry / 365.0;
            double S = stockPrice.doubleValue();
            double K = option.getStrikePrice().doubleValue();
            double sigma = option.getImpliedVolatility();

            BigDecimal newPrice;
            if (option.getOptionType() == OptionType.CALL) {
                newPrice = blackScholesService.calculateCallPrice(S, K, T, sigma);
            } else {
                newPrice = blackScholesService.calculatePutPrice(S, K, T, sigma);
            }

            option.setPrice(newPrice);
            // R1 761: deljeni ask/bid spread (±5%) — ista konstanta kao u generatoru.
            option.setAsk(OptionGeneratorService.askFrom(newPrice));
            option.setBid(OptionGeneratorService.bidFrom(newPrice));
            // R1 458: osvezi maintenance margin po TRENUTNOJ ceni akcije + stvarnom contractSize.
            option.setMaintenanceMargin(
                    optionGeneratorService.computeMaintenanceMargin(option.getContractSize(), stockPrice));
            changed.add(option);
        }

        optionRepository.saveAll(changed);
        log.info("Rekalkulisane cene za {} opcija", changed.size());
    }
}
