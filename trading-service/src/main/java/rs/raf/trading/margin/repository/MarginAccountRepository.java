package rs.raf.trading.margin.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import rs.raf.trading.margin.model.CompanyMarginAccount;
import rs.raf.trading.margin.model.MarginAccount;
import rs.raf.trading.margin.model.MarginAccountStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarginAccountRepository extends JpaRepository<MarginAccount, Long> {

    /**
     * Pronalazi sve margin racune za datog korisnika (USER vlasnistvo).
     */
    List<MarginAccount> findByUserId(Long userId);

    /**
     * BE-STK-06: pronalazi marzni racun kompanije (COMPANY vlasnistvo) po
     * {@code companyId}. {@code company_id} kolona je samo na
     * {@link CompanyMarginAccount} potklasi, pa upit ide nad konkretnim tipom.
     * Po Marzni_Racuni.txt §57: "kompanija moze imati samo jedan marzni racun" —
     * vraca prvi nadjen (treba da bude najvise jedan zbog one-per-company pravila).
     */
    @Query("SELECT c FROM CompanyMarginAccount c WHERE c.companyId = :companyId")
    Optional<CompanyMarginAccount> findByCompanyId(@Param("companyId") Long companyId);

    /**
     * BE-STK-05: pronalazi prvi ACTIVE margin racun za korisnika.
     * Po Marzni_Racuni.txt §57: "korisnik moze imati samo jedan marzni racun".
     */
    Optional<MarginAccount> findFirstByUserIdAndStatus(Long userId, MarginAccountStatus status);

    /**
     * BE-STK-05: pessimistic lock za concurrent BUY/SELL settlement.
     * Sprecava race izmedju paralelnih order fill commit-a, deposit-a i margin call check-a.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MarginAccount m WHERE m.id = :id")
    Optional<MarginAccount> findByIdForUpdate(@Param("id") Long id);

    /**
     * Pronalazi sve margin racune sa datim statusom
     */
    List<MarginAccount> findByStatus(MarginAccountStatus status);

    /**
     * Pronalazi margin racun vezan za dati obicni racun
     */
    List<MarginAccount> findByAccountId(Long accountId);

    /**
     * BE-STK-04 (2-step block, H2 test compat): pronalazi id-eve svih margin
     * racuna ciji je RASPOLOZIVI initialMargin (IM − reservedMargin) ispod
     * maintenanceMargin, a jos uvek su ACTIVE. Caller (servis) potom poziva
     * {@link #bulkUpdateStatus} sa istom listom da ih atomicno flipne na BLOCKED.
     *
     * <p><b>P1-margin-1 (R2 1326 / R3 1548) — kasni margin call:</b> ranije je
     * uslov bio {@code maintenance_margin > initial_margin} (SIROV IM, ignorisao
     * {@code reservedMargin}). To je bilo nekonzistentno sa {@code withdraw}
     * guard-om (P1-8) koji vec koristi {@code IM − reservedMargin < MM}: racun sa
     * velikim {@code reservedMargin} (hold za in-flight margin BUY) mogao je da ima
     * RASPOLOZIVU marzu ispod MM a da ga dnevni scheduler NE blokira (margin call
     * kasni — banka izlozena). Sada oba puta (scheduler + post-fill
     * {@code MarginOrderSettlementService.checkMarginCallAndBlock}) koriste isti
     * raspolozivi prag {@code (IM − reservedMargin) < MM}.
     *
     * <p>{@code reservedMargin} je nullable u legacy redovima → {@code COALESCE}
     * na 0 da uslov ne otpadne za stare zapise.
     *
     * <p>Was atomic UPDATE...RETURNING (PG-only). Now 2-step JPA for H2 test
     * compat. Race window: between SELECT and UPDATE, concurrent deposit moze
     * deactivate a row — handled via per-row check inside transaction context
     * (depozit ide nad ACTIVE redom sa pessimistic lock; ako je u medjuvremenu
     * flipnut na BLOCKED, depozit puca; sa druge strane scheduler trci unutar
     * @Transactional pa svi nadjeni id-evi se UPDATE-uju u istoj Tx).
     */
    @Query("SELECT m.id FROM MarginAccount m "
            + "WHERE (m.initialMargin - COALESCE(m.reservedMargin, 0)) < m.maintenanceMargin "
            + "AND m.status = :activeStatus")
    List<Long> findEligibleForBlock(@Param("activeStatus") MarginAccountStatus activeStatus);

    /**
     * BE-STK-04: bulk UPDATE statusa za listu id-eva. Caller je
     * {@link #findEligibleForBlock} → flip ACTIVE → BLOCKED unutar iste Tx.
     */
    @Modifying
    @Query("UPDATE MarginAccount m SET m.status = :blockedStatus WHERE m.id IN :ids")
    int bulkUpdateStatus(@Param("ids") List<Long> ids, @Param("blockedStatus") MarginAccountStatus blockedStatus);
}
