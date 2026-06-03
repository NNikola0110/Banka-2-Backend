package rs.raf.banka2_bek.loan.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import rs.raf.banka2_bek.loan.dto.*;
import rs.raf.banka2_bek.loan.model.LoanStatus;
import rs.raf.banka2_bek.loan.model.LoanType;
import rs.raf.banka2_bek.loan.service.LoanService;

import java.util.List;

@RestController
@RequestMapping("/loans")
@RequiredArgsConstructor
public class LoanController {

    private final LoanService loanService;

    // --- Client endpoints ---

    @PostMapping
    public ResponseEntity<LoanRequestResponseDto> applyForLoan(@Valid @RequestBody LoanRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(loanService.createLoanRequest(request, getEmail()));
    }

    @GetMapping("/my")
    public ResponseEntity<Page<LoanResponseDto>> getMyLoans(
            @PageableDefault(size = 20, sort = "amount", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(loanService.getMyLoans(getEmail(), pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<LoanResponseDto> getLoanById(@PathVariable Long id) {
        return ResponseEntity.ok(loanService.getLoanById(id));
    }

    @GetMapping("/{id}/installments")
    public ResponseEntity<List<InstallmentResponseDto>> getInstallments(@PathVariable Long id) {
        return ResponseEntity.ok(loanService.getInstallments(id));
    }

    /**
     * ACCEPTED-DEVIATION (user-directed 03.06): prevremena otplata vise NE zahteva OTP.
     */
    @PostMapping("/{id}/early-repayment")
    public ResponseEntity<LoanResponseDto> earlyRepayment(@PathVariable Long id) {
        return ResponseEntity.ok(loanService.earlyRepayment(id, getEmail()));
    }

    @GetMapping("/requests/my")
    public ResponseEntity<List<LoanRequestResponseDto>> getMyLoanRequests() {
        return ResponseEntity.ok(loanService.getMyLoanRequests(getEmail()));
    }

    // --- Employee/Admin endpoints ---

    @GetMapping("/requests")
    public ResponseEntity<Page<LoanRequestResponseDto>> getLoanRequests(
            @RequestParam(required = false) LoanStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(loanService.getLoanRequests(status, pageable));
    }

    @PatchMapping("/requests/{id}/approve")
    public ResponseEntity<LoanResponseDto> approveLoan(@PathVariable Long id) {
        return ResponseEntity.ok(loanService.approveLoanRequest(id));
    }

    @PatchMapping("/requests/{id}/reject")
    public ResponseEntity<LoanRequestResponseDto> rejectLoan(@PathVariable Long id) {
        return ResponseEntity.ok(loanService.rejectLoanRequest(id));
    }

    @GetMapping
    public ResponseEntity<Page<LoanResponseDto>> getAllLoans(
            @RequestParam(required = false) LoanType loanType,
            @RequestParam(required = false) LoanStatus status,
            @RequestParam(required = false) String accountNumber,
            @PageableDefault(size = 20, sort = "amount", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(loanService.getAllLoans(loanType, status, accountNumber, pageable));
    }

    private String getEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        return principal.toString();
    }
}
