package rs.raf.trading.analytics;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * [W3-T2] Read-only REST endpoint za Spark "analytics_daily" output.
 *
 * <p>Zasticen na ADMIN/SUPERVISOR — autorizacija se konfigurise centralno u
 * {@code TradingSecurityConfig} (paritet sa {@code /tax/**} pattern-om u istom
 * fajlu). Bez {@code @PreAuthorize} na metodama da centralizujemo politiku.
 *
 * <p><b>Napomena o adaptaciji:</b> plan W3-T2 ostavlja opciju da
 * {@code AnalyticsController} bude u {@code banka2_bek} (banka-core), ali pošto
 * je {@code analytics_daily} tabela fizički u {@code trading_db} (videti
 * {@code trading-db-init/02-analytics-tables.sql}), kontroler je smešten OVDE
 * (trading-service) da direktno cita iz lokalne JPA — bez dodatnog
 * {@code @SecondaryDataSource} bean-a ili IPC poziva. FE poziva apsolutnu rutu
 * {@code /admin/analytics/daily} koja je rutirana preko api-gateway-a na
 * trading-service:8082.
 */
@RestController
@RequestMapping("/admin/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    /**
     * Vraca sve metrike za dati datum, opciono filtrirano po {@code metric_name}.
     *
     * @param date obavezan ISO datum (yyyy-MM-dd); npr. {@code ?date=2026-05-26}
     * @param metricName opciono — npr. {@code top_movers}, {@code sector_perf},
     *                   {@code klijent_activity}
     */
    @GetMapping("/daily")
    public ResponseEntity<List<AnalyticsDailyDto>> getDaily(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(value = "metric_name", required = false) String metricName) {
        return ResponseEntity.ok(analyticsService.findDaily(date, metricName));
    }
}
