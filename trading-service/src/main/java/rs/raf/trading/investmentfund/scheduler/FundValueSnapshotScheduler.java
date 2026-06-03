package rs.raf.trading.investmentfund.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.investmentfund.model.ClientFundPosition;
import rs.raf.trading.investmentfund.model.FundValueSnapshot;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.ClientFundPositionRepository;
import rs.raf.trading.investmentfund.repository.FundValueSnapshotRepository;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.investmentfund.service.FundValueCalculator;

import jakarta.annotation.PreDestroy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * NAPOMENA (post-cutover 2f): dnevni {@code @Scheduled} cron {@link #snapshotAllFunds}
 * je AKTIVAN — {@link rs.raf.trading.config.SchedulingConfig} nosi {@code @EnableScheduling}
 * (gejtovano {@code trading.scheduling.enabled}, uspavan samo u test profilu). Monolitna
 * kopija snapshot schedulera je ugasena cutover-om, pa trading-service jedini snima
 * vrednosti fondova.
 *
 * {@code @EventListener(ApplicationReadyEvent)} ({@link #onStartupSnapshotAllFunds})
 * okida se na startup-u trading-service (nezavisno od {@code @EnableScheduling}).
 * Ono pokriva dva slucaja: (1) odmah po ApplicationReady-u (restart na vec popunjenoj
 * bazi), i (2) jedan odlozeni "post-seed" snapshot 90s kasnije preko jednokratnog
 * {@link ScheduledExecutorService} task-a — dovoljno da Docker seed servis ubaci fondove
 * pre nego sto klijent otvori FundDetailsPage. Time je uklonjen
 * {@code @Scheduled(fixedDelay=Long.MAX_VALUE)} "run-once" hack (R2 1445).
 * Ako {@code trading_db} nema fond podatke, {@code findByActiveTrueOrderByNameAsc()}
 * vrati praznu listu i petlja ne pravi nijedan {@code BankaCoreClient} poziv (no-op).
 *
 * {@link #snapshotFundIfMissing} NIJE scheduler — to je idempotentni helper
 * koji {@code InvestmentFundService.invest/withdraw} zove posle uspesne
 * operacije; balans fond racuna se cita preko banka-core internog API-ja
 * ({@link BankaCoreClient#getAccount}) jer racuni zive u banka-core domenu.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FundValueSnapshotScheduler {

    private final InvestmentFundRepository investmentFundRepository;
    private final FundValueCalculator fundValueCalculator;
    private final FundValueSnapshotRepository fundValueSnapshotRepository;
    private final BankaCoreClient bankaCoreClient;
    private final ClientFundPositionRepository clientFundPositionRepository;

    /** Jednokratni izvrsilac za odlozeni post-seed snapshot (R2 1445 — zamena za fixedDelay-hack). */
    private final ScheduledExecutorService postSeedExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fund-snapshot-post-seed");
                t.setDaemon(true);
                return t;
            });

    private static final long POST_SEED_DELAY_SECONDS = 90L;

    /**
     * Bag prijavljen 10.05.2026: FundDetailsPage prikazuje "Performanse fonda"
     * graf prazan dok god u {@code fund_value_snapshots} ne postoji ijedan red
     * (FE filtruje per-period — ako nema snapshot za period, prikaze placeholder
     * "Nema podataka o performansama"). Cron-trigger pravi snapshot u 23:45,
     * ali svaki novi fond i svaki podizajan stack pre tog vremena pokazuje
     * graf prazan, sto deluje kao bag iako je samo "rano".
     *
     * Resenje sa dva sloja (oba kroz JEDAN startup hook, R2 1445):
     *  1) odmah po ApplicationReady-u — pokriva BE restart na vec popunjenoj bazi.
     *  2) jednokratni odlozeni snapshot {@value #POST_SEED_DELAY_SECONDS}s kasnije —
     *     dovoljno da Docker seed servis (depends_on: backend healthy) ubaci fondove,
     *     pa snapshot sve pokrije pre nego sto klijent otvori FundDetailsPage.
     *
     * Oba prolaza su idempotentna preko {@code existsByFundIdAndSnapshotDate}
     * guard-a — nema duplikata cak i kad se stack restartuje vise puta u
     * toku istog dana.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartupSnapshotAllFunds() {
        try {
            snapshotAllFunds();
        } catch (Exception e) {
            log.warn("Fund snapshot init pri startup-u nije uspeo: {}", e.getMessage());
        }
        postSeedExecutor.schedule(() -> {
            try {
                snapshotAllFunds();
            } catch (Exception e) {
                log.warn("Fund snapshot {}s post-seed nije uspeo: {}", POST_SEED_DELAY_SECONDS, e.getMessage());
            }
        }, POST_SEED_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdownPostSeedExecutor() {
        postSeedExecutor.shutdownNow();
    }

    /**
     * Idempotent helper koji InvestmentFundService.invest/withdraw zove
     * posle uspesne operacije — garantuje da fond koji je upravo imao
     * cash flow ima makar 1 snapshot za danas, cak i ako post-seed
     * scheduler nije stigao da se okine (recimo pri brz BE restart).
     */
    public void snapshotFundIfMissing(InvestmentFund fund) {
        if (fund == null || fund.getId() == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        if (fundValueSnapshotRepository.existsByFundIdAndSnapshotDate(fund.getId(), today)) {
            return;
        }
        try {
            buildAndSaveSnapshot(fund, today);
        } catch (Exception e) {
            log.warn("Inline snapshot za fond #{} nije uspeo: {}", fund.getId(), e.getMessage());
        }
    }

    @Scheduled(cron = "0 45 23 * * *")
    public void snapshotAllFunds() {
        LocalDate today = LocalDate.now();
        List<InvestmentFund> funds = investmentFundRepository.findByActiveTrueOrderByNameAsc();
        log.info("Fund snapshot: {} active funds for {}", funds.size(), today);

        for (InvestmentFund fund : funds) {
            try {
                if (fundValueSnapshotRepository.existsByFundIdAndSnapshotDate(fund.getId(), today)) {
                    continue;
                }
                buildAndSaveSnapshot(fund, today);
            } catch (Exception e) {
                log.error("Failed to snapshot fund #{}: {}", fund.getId(), e.getMessage());
            }
        }
    }

    /**
     * R1 790 — deljeni "build + save" snapshot blok (ranije verbatim dupliran u
     * {@link #snapshotFundIfMissing} i {@link #snapshotAllFunds}). Caller je odgovoran
     * za {@code existsByFundIdAndSnapshotDate} guard pre poziva (idempotentnost).
     */
    private void buildAndSaveSnapshot(InvestmentFund fund, LocalDate today) {
        BigDecimal fundValue = fundValueCalculator.computeFundValue(fund);
        BigDecimal profit = fundValueCalculator.computeProfit(fund);
        BigDecimal liquidAmount = nullToZero(bankaCoreClient.getAccount(fund.getAccountId()).balance());
        BigDecimal investedTotal = clientFundPositionRepository.findByFundId(fund.getId()).stream()
                .map(ClientFundPosition::getTotalInvested)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        FundValueSnapshot snapshot = new FundValueSnapshot();
        snapshot.setFundId(fund.getId());
        snapshot.setSnapshotDate(today);
        snapshot.setFundValue(fundValue);
        snapshot.setLiquidAmount(liquidAmount);
        snapshot.setInvestedTotal(investedTotal);
        snapshot.setProfit(profit);
        fundValueSnapshotRepository.save(snapshot);
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
