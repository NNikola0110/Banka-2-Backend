package rs.raf.banka2_bek.loan.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.banka2_bek.loan.model.LoanInstallment;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LoanInstallmentRepository extends JpaRepository<LoanInstallment, Long> {

    List<LoanInstallment> findByLoanIdOrderByExpectedDueDateAsc(Long loanId);

    List<LoanInstallment> findByExpectedDueDateLessThanEqualAndPaidFalse(LocalDate date);

    /**
     * P0-B2 (double-charge guard): pesimisticki lock na pojedinacnu ratu.
     *
     * <p>Scheduler ucita listu dospelih rata van transakcije; pod {@code replicas:2}
     * dve instance vide istu ratu i obe je naplate. {@code processOne} re-cita ratu
     * pod ovim lockom i ponovo proverava {@code paid} pre naplate — druga instanca
     * blokira na lock-u, vidi {@code paid=true} i radi no-op (idempotentno).</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM LoanInstallment i WHERE i.id = :id")
    Optional<LoanInstallment> findByIdForUpdate(@Param("id") Long id);

    /**
     * P0-B2 (LATE->ACTIVE reset, Sc38): broj jos dospelih neplacenih rata za kredit
     * (zakljucno sa {@code date}). 0 znaci da kredit vise nije u kasnjenju.
     */
    long countByLoanIdAndPaidFalseAndExpectedDueDateLessThanEqual(Long loanId, LocalDate date);
}
