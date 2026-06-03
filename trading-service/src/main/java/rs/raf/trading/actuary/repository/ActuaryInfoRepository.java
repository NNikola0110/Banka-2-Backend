package rs.raf.trading.actuary.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * NAPOMENA (copy-first ekstrakcija, faza 2c): monolitni repozitorijum je imao
 * upite koji su navigirali kroz {@code a.employee.email / firstName / lastName /
 * position} (JPQL preko {@code @OneToOne Employee} veze). U trading-service-u je
 * {@code Employee} u banka-core domenu, pa se atributi zaposlenog ne mogu
 * filtrirati u JPQL-u. Servisni sloj prvo razresava {@code employeeId}-eve preko
 * {@code BankaCoreClient.findEmployees(...)} / {@code getUserByEmail(...)}, pa onda
 * zove {@code findByEmployeeId} / {@code findByActuaryTypeAndEmployeeIdIn}.
 */
@Repository
public interface ActuaryInfoRepository extends JpaRepository<ActuaryInfo, Long> {

    Optional<ActuaryInfo> findByEmployeeId(Long employeeId);

    List<ActuaryInfo> findAllByActuaryType(ActuaryType actuaryType);

    /**
     * Aktuari datog tipa cije {@code employeeId} pripada prosledjenom skupu.
     * Zamena za monolitni {@code findByTypeAndFilters} — servis prvo razresi
     * skup {@code employeeId}-eva preko {@code BankaCoreClient.findEmployees(...)}
     * (banka-core radi filtriranje po email/firstName/lastName/position).
     */
    List<ActuaryInfo> findByActuaryTypeAndEmployeeIdIn(ActuaryType actuaryType,
                                                       Collection<Long> employeeIds);

    /**
     * Resetuje usedLimit na 0 za sve <b>agente</b> u jednom bulk UPDATE upitu.
     * Vraca broj azuriranih redova.
     *
     * <p>R1-740 (over-reset spec-dev): {@code WHERE a.actuaryType = AGENT} —
     * supervizori nemaju dnevni limit (usedLimit im je {@code null}/neupotrebljiv),
     * pa ih dnevni cron reset NE sme dirati. Spec (Celina 3 — Aktuari) trazi reset
     * "svih agenata". Bez ovog filtera bulk je resetovao i SUPERVISOR redove
     * (bezopasno po novcu, ali kontradiktorno servisnoj {@code resetAllUsedLimits()}
     * metodi koja je AGENT-only). Sad su bulk (scheduler) i service putanja
     * konzistentne — obe samo agenti.
     *
     * <p>P2-concurrency-locks-1 (R1-443): bulk UPDATE MORA da inkrementira
     * {@code @Version} (`a.version = a.version + 1`). Bez toga JPA-bulk zaobilazi
     * optimisticko zakljucavanje — paralelni order-engine increment
     * ({@code mutateActuaryWithRetry}, @Version-protected) bi mogao da commit-uje nad
     * stale verzijom koju je reset u medjuvremenu prepisao (lost-update: usedLimit se
     * vrati na 0 a onda inkrement upise current+delta nad verzijom koju reset nije
     * podigao → agentov dnevni limit pogresan). Podizanjem version-a, svaki konkurentni
     * optimisticki pisac vidi version-mismatch i retry-uje sa svezim (0) stanjem.
     * {@code clearAutomatically=true} cisti persistence context da kesirani entiteti
     * ne pregaze bulk promenu.</p>
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ActuaryInfo a SET a.usedLimit = 0, a.version = COALESCE(a.version, 0) + 1 "
            + "WHERE a.actuaryType = rs.raf.trading.actuary.model.ActuaryType.AGENT")
    int resetAllUsedLimits();
}
