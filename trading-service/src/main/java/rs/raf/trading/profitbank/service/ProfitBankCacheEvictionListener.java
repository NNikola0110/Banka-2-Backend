package rs.raf.trading.profitbank.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import rs.raf.trading.order.event.OrderCompletedEvent;

/**
 * Sluša {@link OrderCompletedEvent} i invalidira <code>actuary-profit</code>
 * Caffeine cache kad order predje u DONE.
 *
 * <p>Bez evict-a, supervizor bi video stale profit izracune dok TTL ne istekne
 * (5 min); ovo daje real-time osvezavanje cim fill engine zavrsi nalog.</p>
 *
 * <p>SpEL <code>condition</code> filtrira na zaposlene ordere (ActuaryProfitService
 * vec ignorise CLIENT i FUND), izbegavajuci nepotrebne evict-e za klijentske
 * trgovine. <code>allEntries=true</code> jer cache trenutno ima jedan key.</p>
 *
 * <p>R1 803: {@code OrderCompletedEvent.userRole()} dolazi iz
 * {@code order.getUserRole()} koji nosi domenski string (EMPLOYEE/CLIENT/FUND),
 * NIKAD Spring-prefiks {@code ROLE_EMPLOYEE} — pa je ranija
 * {@code || == 'ROLE_EMPLOYEE'} grana bila mrtva i uklonjena.</p>
 *
 * <p>NAPOMENA (copy-first ekstrakcija, faza 2d-E): {@link OrderCompletedEvent}
 * je {@code rs.raf.trading.order.event.OrderCompletedEvent} — emit-uje ga
 * lokalni {@code OrderExecutionService}. Veza event -&gt; listener je u
 * potpunosti unutar trading-service JVM-a; kopirano verbatim (samo package
 * rename).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfitBankCacheEvictionListener {

    @EventListener
    @CacheEvict(
            value = ProfitBankCacheConfig.ACTUARY_PROFIT_CACHE,
            allEntries = true,
            condition = "#event.userRole() == 'EMPLOYEE'"
    )
    public void onOrderCompleted(OrderCompletedEvent event) {
        log.debug("Evicting actuary-profit cache after order #{} ({} #{}) completed",
                event.orderId(), event.userRole(), event.userId());
    }
}
