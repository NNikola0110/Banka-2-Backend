package rs.raf.trading.tax.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rs.raf.trading.notification.model.NotificationType;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.tax.service.TaxCalculationException;
import rs.raf.trading.tax.service.TaxService;

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
 * NAPOMENA (copy-first ekstrakcija, faza 2c): {@code @Scheduled} je USPAVAN —
 * {@code TradingServiceApplication} namerno NEMA {@code @EnableScheduling} do
 * cutover-a (2f). Monolit i dalje vrti svoj {@code TaxScheduler}; trading-service
 * ga ne okida da se obracun poreza ne bi izvrsavao dvaput.
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
     * BE-ORD-08: emituje notifikaciju supervizoru kada FX rate nije dostupan
     * pa neki user-month obracun ne moze biti tacno izvrsen. Best-effort:
     * ako notify padne, samo logujemo (ne zelimo da pad notifikacije pretvori
     * tax scheduler u beskoran retry).
     *
     * <p>{@code recipientId=null} signalizuje "broadcast supervizorima" —
     * NotificationService impl moze da rezolvuje supervisor sve receivere
     * preko banka-core seam-a, ili da samo logguje za sada (best-effort).
     */
    private void notifySupervisorOfTaxFailure(TaxCalculationException txEx) {
        try {
            String body = "Tax calculation failed for user "
                    + (txEx.getUserId() != null ? txEx.getUserId() : "?")
                    + " (" + (txEx.getUserType() != null ? txEx.getUserType() : "?") + ") "
                    + "due to FX rate unavailability. Razlog: " + txEx.getMessage()
                    + ". Pokrenuti retry kad FX kursevi budu dostupni.";
            notificationService.notify(
                    null,
                    "SUPERVISOR",
                    NotificationType.GENERAL,
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
