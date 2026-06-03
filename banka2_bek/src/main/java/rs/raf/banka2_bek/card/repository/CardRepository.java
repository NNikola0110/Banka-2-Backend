package rs.raf.banka2_bek.card.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.banka2_bek.card.model.Card;
import rs.raf.banka2_bek.card.model.CardStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CardRepository extends JpaRepository<Card, Long> {
    List<Card> findByClientId(Long clientId);
    List<Card> findByAccountId(Long accountId);
    Optional<Card> findByCardNumber(String cardNumber);

    /**
     * P2-2 lost-update fix: pessimistic write lock na red kartice. Top-up/withdraw
     * rade read-modify-write na {@code prepaidBalance}; bez lock-a NA KARTICI dva
     * paralelna top-up-a iz RAZLICITIH izvornih racuna zakljucavaju razlicite
     * account redove, ne serijalizuju se, oba citaju isti balance i poslednji
     * write pregazi prvi (klijent gubi novac). Lock na kartici serijalizuje sve
     * mutacije balance-a iste kartice. Ogledalo {@code AccountRepository.findForUpdateById}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Card c WHERE c.id = :id")
    Optional<Card> findByIdForUpdate(@Param("id") Long id);
    long countByAccountIdAndStatusNot(Long accountId, rs.raf.banka2_bek.card.model.CardStatus status);
    long countByAccountIdAndClientIdAndStatusNot(Long accountId, Long clientId, rs.raf.banka2_bek.card.model.CardStatus status);

    /**
     * Aktivne (non-DEACTIVATED) kartice za dati (account, client) par.
     * P2.3 — koristi se za alokaciju slota 1/2 pre kreiranja nove kartice.
     */
    List<Card> findByAccountIdAndClientIdAndStatusNot(
            Long accountId, Long clientId, rs.raf.banka2_bek.card.model.CardStatus status);

    /**
     * R1 317: kartice koje su istekle ({@code expirationDate < cutoff}) ali jos
     * NISU DEACTIVATED (tj. ACTIVE ili BLOCKED). Koristi ih
     * {@code CardExpiryScheduler} da ih prebaci u DEACTIVATED (istekla kartica
     * vise NE sme da bude upotrebljiva — usage gate je {@code status == ACTIVE}).
     */
    @Query("SELECT c FROM Card c WHERE c.expirationDate < :cutoff AND c.status <> :excludedStatus")
    List<Card> findExpiredWithStatusNot(@Param("cutoff") LocalDate cutoff,
                                        @Param("excludedStatus") CardStatus excludedStatus);
}
