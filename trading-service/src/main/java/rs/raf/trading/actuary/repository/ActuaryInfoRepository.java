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
     * Resetuje usedLimit na 0 za sve aktuare u jednom bulk UPDATE upitu.
     * Vraca broj azuriranih redova.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ActuaryInfo a SET a.usedLimit = 0")
    int resetAllUsedLimits();
}
