package rs.raf.trading.tax.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.trading.notification.model.NotificationType;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.tax.service.TaxCalculationException;
import rs.raf.trading.tax.service.TaxService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Test {@link TaxScheduler} — porten verbatim iz monolita (faza 2c, samo
 * package rename). {@code @Scheduled} je inertan; metoda se poziva eksplicitno.
 *
 * <p>BE-ORD-08: dodati testovi za {@link TaxCalculationException} handling —
 * scheduler hvata exception po-korisniku, emituje notifikaciju supervizoru,
 * ali ne propagira (ne sme da padne ceo cron).
 */
@ExtendWith(MockitoExtension.class)
class TaxSchedulerTest {

    @Mock
    private TaxService taxService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private TaxScheduler taxScheduler;

    @Nested
    @DisplayName("calculateMonthlyTax")
    class CalculateMonthlyTax {

        @Test
        @DisplayName("calls taxService.calculateTaxForAllUsers() exactly once")
        void callsTaxServiceOnce() {
            taxScheduler.calculateMonthlyTax();

            verify(taxService, times(1)).calculateTaxForAllUsers();
        }

        @Test
        @DisplayName("does not propagate exception from taxService")
        void doesNotPropagateException() {
            doThrow(new RuntimeException("DB error")).when(taxService).calculateTaxForAllUsers();

            taxScheduler.calculateMonthlyTax();

            verify(taxService, times(1)).calculateTaxForAllUsers();
        }

        @Test
        @DisplayName("catches all exception subtypes including RuntimeException")
        void catchesAllExceptionTypes() {
            doThrow(new IllegalStateException("bad state")).when(taxService).calculateTaxForAllUsers();

            taxScheduler.calculateMonthlyTax();

            verify(taxService, times(1)).calculateTaxForAllUsers();
        }

        @Test
        @DisplayName("BE-ORD-08 + OT-1061: TaxCalculationException → notifySupervisors (TAX_CALCULATION_FAILED) with userId/userType")
        void taxCalculationExceptionEmitsSupervisorNotification() {
            doThrow(new TaxCalculationException(42L, "CLIENT",
                    "FX rate unavailable for USD", new RuntimeException("timeout")))
                    .when(taxService).calculateTaxForAllUsers();

            taxScheduler.calculateMonthlyTax();

            // OT-1061 [FIXED]: scheduler vise NE zove no-op notify(null,SUPERVISOR,GENERAL),
            // nego notifySupervisors sa in-app-sending TAX_CALCULATION_FAILED tipom —
            // NotificationServiceImpl razresi realne supervizore i svakom posalje in-app.
            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService, times(1)).notifySupervisors(
                    eq(NotificationType.TAX_CALCULATION_FAILED),
                    eq("Obracun poreza neuspesan (FX)"),
                    bodyCaptor.capture(),
                    eq("TAX"),
                    eq(42L)
            );
            // Stari (no-op) notify ugovor se vise NE koristi.
            verify(notificationService, never())
                    .notify(any(), any(), any(), any(), any(), any(), any());
            assertThat(bodyCaptor.getValue())
                    .contains("42")
                    .contains("CLIENT")
                    .contains("FX rate unavailable for USD");
        }

        @Test
        @DisplayName("BE-ORD-08: notifySupervisors failure ne propagira (best-effort)")
        void taxCalculationExceptionNotifyFailureIsSwallowed() {
            doThrow(new TaxCalculationException(1L, "CLIENT", "FX dead", null))
                    .when(taxService).calculateTaxForAllUsers();
            doThrow(new RuntimeException("banka-core down")).when(notificationService).notifySupervisors(
                    any(), any(), any(), any(), any());

            // Ne sme da padne — scheduler mora da bude rezilijentan na notify pad.
            taxScheduler.calculateMonthlyTax();

            verify(notificationService).notifySupervisors(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("R1 429: full success → ne emituje supervisor notifikaciju")
        void fullSuccess_doesNotNotifySupervisor() {
            // Bez exception-a iz taxService → nista se ne preskace → bez supervisor notify-ja.
            taxScheduler.calculateMonthlyTax();

            verify(notificationService, never())
                    .notifySupervisors(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("OT-1061 [FIXED]: tax-fail emituje notifySupervisors(TAX_CALCULATION_FAILED), NE no-op GENERAL")
        void taxFailure_emitsNotifySupervisors_withInAppType() {
            // OT-1061 [FIXED 02.06]: raniji poziv notify(null, "SUPERVISOR", GENERAL, ...)
            // je bio DOUBLE no-op (recipientId=null guard + GENERAL oba kanala false) →
            // supervizor NIKAD nije primio tax-fail notifikaciju. Sada scheduler zove
            // notifySupervisors sa in-app-sending TAX_CALCULATION_FAILED tipom; impl
            // razresi realne supervizore (banka-core) i svakom posalje in-app.
            doThrow(new TaxCalculationException(7L, "CLIENT", "FX rate unavailable for USD", null))
                    .when(taxService).calculateTaxForAllUsers();

            taxScheduler.calculateMonthlyTax();

            verify(notificationService, times(1)).notifySupervisors(
                    eq(NotificationType.TAX_CALCULATION_FAILED), // in-app-sending (NE GENERAL no-op)
                    eq("Obracun poreza neuspesan (FX)"),
                    any(),
                    eq("TAX"),
                    eq(7L));
            // Stari no-op notify(...) ugovor se vise NE poziva.
            verify(notificationService, never())
                    .notify(any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  OT-1065 (TEST-tr-tax-actuary-exchange-1) — cron raspored/izraz
    //  Mesecni obracun se okida "0 0 0 1 * *" = 00:00:00 prvog dana svakog meseca.
    //  Spec Celina 3 §"na kraju meseca" (settlementPeriod settle-uje PRETHODNI mesec).
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("cron raspored (OT-1065)")
    class CronSchedule {

        @Test
        @DisplayName("calculateMonthlyTax nosi @Scheduled(cron = \"0 0 0 1 * *\")")
        void calculateMonthlyTax_hasMonthlyFirstDayCron() throws NoSuchMethodException {
            java.lang.reflect.Method m =
                    TaxScheduler.class.getMethod("calculateMonthlyTax");
            org.springframework.scheduling.annotation.Scheduled scheduled =
                    m.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);

            assertThat(scheduled).as("calculateMonthlyTax mora biti @Scheduled").isNotNull();
            // sekunda minut sat dan-u-mesecu mesec dan-u-nedelji → 1. u mesecu 00:00:00
            assertThat(scheduled.cron()).isEqualTo("0 0 0 1 * *");
        }

        @Test
        @DisplayName("cron je validan Spring CronExpression i prvi okidaj je 00:00 prvog dana sledeceg meseca")
        void cron_parsesAndNextTriggerIsFirstOfMonthMidnight() {
            org.springframework.scheduling.support.CronExpression expr =
                    org.springframework.scheduling.support.CronExpression.parse("0 0 0 1 * *");

            // Krenuvsi od sredine meseca, sledeci okidaj je 1. sledeceg meseca u ponoc.
            java.time.LocalDateTime from = java.time.LocalDateTime.of(2026, 6, 15, 12, 30, 0);
            java.time.LocalDateTime next = expr.next(from);

            assertThat(next).isEqualTo(java.time.LocalDateTime.of(2026, 7, 1, 0, 0, 0));
        }
    }
}
