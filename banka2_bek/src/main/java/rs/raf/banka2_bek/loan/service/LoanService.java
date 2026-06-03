package rs.raf.banka2_bek.loan.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import rs.raf.banka2_bek.loan.dto.*;
import rs.raf.banka2_bek.loan.model.LoanStatus;
import rs.raf.banka2_bek.loan.model.LoanType;

import java.util.List;

public interface LoanService {

    LoanRequestResponseDto createLoanRequest(LoanRequestDto request, String clientEmail);

    Page<LoanRequestResponseDto> getLoanRequests(LoanStatus status, Pageable pageable);

    LoanResponseDto approveLoanRequest(Long requestId);

    LoanRequestResponseDto rejectLoanRequest(Long requestId);

    Page<LoanResponseDto> getMyLoans(String clientEmail, Pageable pageable);

    Page<LoanResponseDto> getAllLoans(LoanType loanType, LoanStatus status, String accountNumber, Pageable pageable);

    LoanResponseDto getLoanById(Long loanId);

    List<InstallmentResponseDto> getInstallments(Long loanId);

    /**
     * ACCEPTED-DEVIATION (user-directed 03.06): earlyRepayment vise NE zahteva OTP.
     * OTP/verifikacija vazi iskljucivo za placanja i transfere (money-out); krediti
     * nisu placanja.
     */
    LoanResponseDto earlyRepayment(Long loanId, String clientEmail);

    List<LoanRequestResponseDto> getMyLoanRequests(String clientEmail);
}
