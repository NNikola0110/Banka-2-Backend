package rs.raf.trading.tax.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rs.raf.trading.notification.model.NotificationType;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.tax.service.TaxCalculationException;
import rs.raf.trading.tax.service.TaxService;
import rs.raf.trading.tax.util.TaxRealizedGainCalculator;

/**
 * Scheduler za automatski obracun poreza.
 * <p>
 * Pokrece se prvog dana svakog meseca u ponoc (00:00:00).
 * Poziva TaxService.calculateTaxForAllUsers() koji obracunava porez
 * na osnovu svih DONE ordera za svakog korisnika.
 * <p>
 * Specifikacija: Celina 3 - Porez na kapitalnu dobit (15%)
 * <p>
 * Nakon obracuna, loguje notifikaciju o poreskim obavezama.
 * Kada se implementira TaxEmailTemplate, ovde dodati slanje emailova
 * korisnicima ciji se taxOwed promenio (koristeci MailNotificationService).
 * <p>
 * NAPOMENA (cutover 2f): {@code @Scheduled} je AKTIVAN — {@link rs.raf.trading.config.SchedulingConfig}
 * nosi {@code @EnableScheduling} gejtovano property-jem {@code trading.scheduling.enabled}
 * (default {@code true}). Posle gasenja monolitne kopije, trading-service je
 * jedini koji okida ovaj obracun. Jedino je u test profilu uspavan
 * ({@code application-test.properties} postavlja {@code trading.scheduling.enabled=false}),
 * pa {@code @SpringBootTest} kontekst ne okida cron usred testa — scheduler testovi
 * pozivaju {@link #calculateMonthlyTax()} eksplicitno.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaxScheduler {

    private final TaxService taxService;
    private final NotificationService notificationService;

    /**
     * Mesecni obracun poreza — pokrece se 1. u mesecu u 00:00:00.
     * <p>
     * Cron format: sekunda minut sat dan-u-mesecu mesec dan-u-nedelji
     * "0 0 0 1 * *" = 00:00:00 prvog dana svakog meseca
     * <p>
     * <b>R1 435 (cron "kraj meseca" — ANALIZIRANO, NAMERNO NEPROMENJENO):</b> spec
     * trazi obracun "na kraju meseca". Cron {@code "0 0 0 1 * *"} okida na granici
     * {@code 00:00:00} 1. dana i — preko {@link TaxRealizedGainCalculator#settlementPeriod}
     * (koja tu TACNU granicu prepoznaje i postavlja settlement na PRETHODNI mesec,
     * {@code ym.minusMonths(1)}) — naplacuje upravo zatvoreni mesec. To je
     * funkcionalno period-close NA KRAJU (prethodnog) meseca: trenutak kad mesec
     * istekne uhvati se cela njegova realizovana dobit. Promena cron izraza na npr.
     * "poslednji dan u mesecu 23:59" bi REGRESIRALA P0-B3 clock-boundary fix —
     * {@code settlementPeriod} cronBoundary detekcija je tvrdo vezana za
     * {@code 00:00:00} 1. dana, a null-timestamp {@code inPeriod} heuristika
     * ({@code period == nowMonth}) zavisi od toga da cron settle-uje PRETHODNI mesec.
     * Drzimo postojeci cron + settlementPeriod par (semanticki "na kraju meseca").
     * <p>
     * BE-ORD-08 + BE-PAY-04: hvata agregatni {@link TaxCalculationException}
     * (najcesce FX rate unavailable za bar jednog korisnika) i salje notifikaciju
     * supervizoru sa {@code userId/userType} prvog preskocenog korisnika. Ostali
     * korisnici u istom batch-u su uspesno obracunati i persistovani — paritet sa
     * BE-PAY-02 (InstallmentProcessor REQUIRES_NEW) i BE-PAY-03 (VariableRateProcessor
     * REQUIRES_NEW); {@code TaxCalculatorProcessor.processOne} sad ima
     * {@code @Transactional(REQUIRES_NEW)} pa pad jednog ne rollback-uje druge.
     */
    @Scheduled(cron = "0 0 0 1 * *")
    public void calculateMonthlyTax() {
        log.info("Starting monthly tax calculation...");
        try {
            taxService.calculateTaxForAllUsers();
            log.info("Monthly tax calculation completed successfully.");
            log.info("Tax calculation complete. Email notifications for users with outstanding tax would be sent here.");
        } catch (TaxCalculationException txEx) {
            // BE-ORD-08: FX/conversion failure za jednog ili vise korisnika.
            // Obracun ostalih korisnika je vec persistovan u TaxService petlji.
            log.error("Tax calculation skipped for user {} ({}): {}",
                    txEx.getUserId(), txEx.getUserType(), txEx.getMessage());
            notifySupervisorOfTaxFailure(txEx);
        } catch (Exception e) {
            log.error("Error during tax calculation: {}", e.getMessage(), e);
        }
    }

    /**
     * BE-ORD-08 + OT-1061: emituje notifikaciju SVAKOM supervizoru kada FX rate
     * nije dostupan pa neki user-month obracun ne moze biti tacno izvrsen.
     *
     * <p><b>OT-1061 [BUG-FOUND fix]:</b> raniji poziv
     * {@code notify(null, "SUPERVISOR", GENERAL, ...)} je bio DOUBLE no-op —
     * (1) {@code recipientId=null} rani guard preskoci oba kanala; (2) i da nije,
     * {@code GENERAL} ima {@code sendsEmail=false} + {@code sendsInApp=false}.
     * Posledica: supervizor NIKAD nije primio tax-FX-failure notifikaciju. Sada se
     * preko {@link NotificationService#notifySupervisors} razrese realni supervizori
     * (banka-core {@code /internal/users/supervisors}) i svakom posalje in-app
     * notifikacija tipa {@link NotificationType#TAX_CALCULATION_FAILED} (in-app bell).
     *
     * <p>Best-effort: {@code notifySupervisors} interno hvata sve greske (razresenje
     * supervizora + pojedinacni POST) i ne propagira — ne zelimo da pad notifikacije
     * pretvori tax scheduler u beskoran retry. Dodatni try/catch ostaje kao pojas i
     * tregeri za bilo koji neocekivani throw.
     */
    private void notifySupervisorOfTaxFailure(TaxCalculationException txEx) {
        try {
            String body = "Tax calculation failed for user "
                    + (txEx.getUserId() != null ? txEx.getUserId() : "?")
                    + " (" + (txEx.getUserType() != null ? txEx.getUserType() : "?") + ") "
                    + "due to FX rate unavailability. Razlog: " + txEx.getMessage()
                    + ". Pokrenuti retry kad FX kursevi budu dostupni.";
            notificationService.notifySupervisors(
                    NotificationType.TAX_CALCULATION_FAILED,
                    "Obracun poreza neuspesan (FX)",
                    body,
                    "TAX",
                    txEx.getUserId()
            );
        } catch (Exception notifyEx) {
            log.warn("Failed to publish supervisor notification for tax failure: {}",
                    notifyEx.getMessage());
        }
    }
}
