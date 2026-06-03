package rs.raf.banka2_bek.transfers.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.banka2_bek.transfers.model.Transfer;
import rs.raf.banka2_bek.client.model.Client;

import java.time.LocalDateTime;
import java.util.List;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    /**
     * R1-653 (perf): jedan upit sa fetch-join-om umesto ucitavanja SVIH transfera
     * pa in-memory filtriranja + N+1 lazy fetch-a (fromAccount/toAccount/currency/
     * createdBy su LAZY pa je {@code mapToDto} ranije okidao 5+ dodatnih SELECT-ova
     * po transferu). Filteri (accountNumber/from/to datum) su sad u WHERE klauzuli;
     * {@code null} parametri se ignorisu.
     */
    @Query("""
            SELECT DISTINCT t FROM Transfer t
              JOIN FETCH t.fromAccount fa
              JOIN FETCH t.toAccount ta
              JOIN FETCH t.fromCurrency
              JOIN FETCH t.toCurrency
              JOIN FETCH t.createdBy
            WHERE t.createdBy = :client
              AND (cast(:accountNumber as string) IS NULL
                   OR fa.accountNumber = :accountNumber
                   OR ta.accountNumber = :accountNumber)
              AND (cast(:fromDateTime as timestamp) IS NULL OR t.createdAt >= :fromDateTime)
              AND (cast(:toDateTime as timestamp) IS NULL OR t.createdAt <= :toDateTime)
            ORDER BY t.createdAt DESC
            """)
    List<Transfer> findForClientWithFilters(@Param("client") Client client,
                                            @Param("accountNumber") String accountNumber,
                                            @Param("fromDateTime") LocalDateTime fromDateTime,
                                            @Param("toDateTime") LocalDateTime toDateTime);
}