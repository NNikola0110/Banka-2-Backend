package rs.raf.banka2_bek.loan.repository;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import rs.raf.banka2_bek.loan.model.InterestType;
import rs.raf.banka2_bek.loan.model.Loan;
import rs.raf.banka2_bek.loan.model.LoanStatus;
import rs.raf.banka2_bek.loan.model.LoanType;

import java.util.ArrayList;
import java.util.List;

public interface LoanRepository
        extends JpaRepository<Loan, Long>, JpaSpecificationExecutor<Loan> {

    List<Loan> findByInterestTypeAndStatusIn(InterestType interestType, List<LoanStatus> statuses);

    Page<Loan> findByClientId(Long clientId, Pageable pageable);

    /**
     * Faza G (live-smoke fix): opcioni admin filteri preneti sa JPQL
     * {@code (:p IS NULL OR col = :p)} na Criteria-API. Stari obrazac je padao na
     * pravom PostgreSQL-u ({@code ERROR: could not determine data type of parameter $N})
     * kada su {@code loanType}/{@code status} {@code null} (enum bind se u {@code :p IS NULL}
     * formi nije mogao tipizirati). Criteria builder bind-uje tipizirane enum parametre →
     * radi i na PG i na H2. Potpis nepromenjen (servis + testovi rade kao pre).
     */
    default Page<Loan> findWithFilters(LoanType loanType, LoanStatus status, String accountNumber, Pageable pageable) {
        Specification<Loan> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (loanType != null) {
                predicates.add(cb.equal(root.get("loanType"), loanType));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (accountNumber != null) {
                predicates.add(cb.equal(root.get("account").get("accountNumber"), accountNumber));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return findAll(spec, pageable);
    }
}
