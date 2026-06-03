package rs.raf.banka2_bek.savings.repository;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.banka2_bek.savings.entity.SavingsDeposit;
import rs.raf.banka2_bek.savings.entity.SavingsDepositStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public interface SavingsDepositRepository
        extends JpaRepository<SavingsDeposit, Long>, JpaSpecificationExecutor<SavingsDeposit> {

    List<SavingsDeposit> findByClientIdOrderByCreatedAtDesc(Long clientId);

    /**
     * R7: deposit-i kojima je kamata dospela ({@code nextInterestPaymentDate <= date})
     * ALI ne preko roka — {@code nextInterestPaymentDate <= maturityDate}. Posle
     * downtime catch-up bez ovog ogranicenja bi pokupio deposit-e cija je sledeca
     * isplata vec prosla maturity i platio kamatu za period koji ne postoji.
     */
    @Query("""
        SELECT d FROM SavingsDeposit d
        WHERE d.status = :status
          AND d.nextInterestPaymentDate <= :date
          AND d.nextInterestPaymentDate <= d.maturityDate
        """)
    List<SavingsDeposit> findDueForInterest(
        @Param("status") SavingsDepositStatus status,
        @Param("date") LocalDate date);

    List<SavingsDeposit> findByStatusAndMaturityDateLessThanEqual(
        SavingsDepositStatus status, LocalDate date);

    /**
     * Faza G (live-smoke fix): opcioni filteri preneti sa JPQL
     * {@code (:p IS NULL OR col = :p)} na Criteria-API. Stari obrazac je padao na
     * pravom PostgreSQL-u ({@code ERROR: could not determine data type of parameter $N})
     * kada je {@code status}/{@code clientId} {@code null} (PG ne moze da zakljuci tip
     * bind-a u {@code :p IS NULL}); enum {@code status} se Criteria builder-om bind-uje
     * tipizirano. Potpis nepromenjen (servis + testovi rade kao pre).
     */
    default Page<SavingsDeposit> adminFindAll(SavingsDepositStatus status, Long clientId, Pageable pageable) {
        Specification<SavingsDeposit> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (clientId != null) {
                predicates.add(cb.equal(root.get("clientId"), clientId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        // Ocuvaj originalni default redosled (JPQL je imao ORDER BY d.createdAt DESC);
        // caller prosledjuje PageRequest.of(page,size) bez Sort-a.
        Pageable effective = pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "createdAt"));
        return findAll(spec, effective);
    }
}
