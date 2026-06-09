package rs.raf.banka2_bek.payment.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.banka2_bek.payment.model.Payment;
import rs.raf.banka2_bek.payment.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * N4 — Payment-level reconciler: stuck inter-bank placanja koja su ostala u
     * PROCESSING duze od cutoff-a. interbankTxIdString != null osigurava da gledamo
     * samo medjubankarska placanja (same-bank placanja se finalizuju sinhrono).
     *
     * <p>Pokriva rupu koju {@code InterbankReconciliationScheduler} (koji gleda samo
     * {@code InterbankTransaction} redove) ne moze: ako je async dispatch task odbijen
     * pre nego sto je {@code execute()} kreirao InterbankTransaction red, Payment ostaje
     * PROCESSING zauvek bez ijednog reda za reconciler da uhvati.
     */
    @Query("""
           select p from Payment p
           where p.status = :status
             and p.createdAt < :cutoff
             and p.interbankTxIdString is not null
           """)
    List<Payment> findStuckInterbankPayments(@Param("status") PaymentStatus status,
                                             @Param("cutoff") LocalDateTime cutoff);

    /**
     * P1-9 — pronadji medjubankarsko placanje po 2PC transaction id paru
     * (routingNumber + idString). Koristi se u {@code GET /interbank/payments/{id}}
     * da bi se za inter-bank transakciju razresilo vlasnistvo (placanje pripada
     * {@code fromAccount.client}) pre nego sto se vrati view DTO.
     */
    Optional<Payment> findByInterbankTxRoutingNumberAndInterbankTxIdString(
            Integer interbankTxRoutingNumber, String interbankTxIdString);

    /**
     * Inbound inter-bank uplata — idempotency guard. Isti COMMIT_TX moze biti
     * retransmitovan (§2.9 duplikat dostava), pa pre upisa INCOMING Payment reda za
     * primljenu uplatu proveravamo da li vec postoji red sa istim 2PC tx parom
     * (routing+id) i istim primaocevim racunom. Tako se ne kreira duplikat istorijskog
     * zapisa pri retry-u COMMIT_TX-a.
     */
    boolean existsByInterbankTxRoutingNumberAndInterbankTxIdStringAndToAccountNumber(
            Integer interbankTxRoutingNumber, String interbankTxIdString, String toAccountNumber);

    // NAPOMENA (PostgreSQL): cast(:param as tip) je neophodan jer PG JDBC
    // ne moze da zakljuci tip NULL parametra — na JPQL ":p is null" izraz
    // genrise "ERROR: could not determine data type of parameter". H2/MySQL
    // ne zahtevaju cast.
    // VAZNO: LEFT JOIN na fromAccount. Inter-bank DOLAZNA placanja imaju
    // fromAccount = NULL (posiljalac je u drugoj banci). Path-navigacija
    // "p.fromAccount.client.id" bi napravila IMPLICITNI INNER JOIN koji bi
    // izbacio te redove PRE OR-grane sa toAccountNumber — pa primalac nikad
    // ne bi video dolazno placanje. Eksplicitni left join cuva NULL-fromAccount redove.
    @Query("""
           select p from Payment p
           left join p.fromAccount fa
           left join fa.client fc
           where (fc.id = :clientId
                  or p.toAccountNumber in (select a.accountNumber from Account a where a.client.id = :clientId))
             and (cast(:fromDate as timestamp) is null or p.createdAt >= :fromDate)
             and (cast(:toDate as timestamp) is null or p.createdAt <= :toDate)
             and (cast(:accountNumber as string) is null or fa.accountNumber = :accountNumber or p.toAccountNumber = :accountNumber)
             and (cast(:minAmount as big_decimal) is null or p.amount >= :minAmount)
             and (cast(:maxAmount as big_decimal) is null or p.amount <= :maxAmount)
             and (cast(:status as string) is null or p.status = :status)
           """)
    Page<Payment> findByUserAccountsWithFilters(
            @Param("clientId") Long clientId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("accountNumber") String accountNumber,
            @Param("minAmount") BigDecimal minAmount,
            @Param("maxAmount") BigDecimal maxAmount,
            @Param("status") PaymentStatus status,
            Pageable pageable
    );
}
