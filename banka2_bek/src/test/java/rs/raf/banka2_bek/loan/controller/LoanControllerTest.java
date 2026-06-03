package rs.raf.banka2_bek.loan.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.raf.banka2_bek.auth.config.GlobalExceptionHandler;
import rs.raf.banka2_bek.loan.dto.InstallmentResponseDto;
import rs.raf.banka2_bek.loan.dto.LoanRequestResponseDto;
import rs.raf.banka2_bek.loan.dto.LoanResponseDto;
import rs.raf.banka2_bek.loan.model.LoanStatus;
import rs.raf.banka2_bek.loan.model.LoanType;
import rs.raf.banka2_bek.loan.service.LoanService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class LoanControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private LoanService loanService;

    @InjectMocks
    private LoanController loanController;

    private LoanResponseDto testLoan;
    private LoanRequestResponseDto testLoanRequest;

    @BeforeEach
    void setUp() {
        // Test isolation: clear leaked SecurityContextHolder state from prior test classes.
        SecurityContextHolder.clearContext();

        mockMvc = MockMvcBuilders
                .standaloneSetup(loanController)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        testLoan = LoanResponseDto.builder()
                .id(1L)
                .loanNumber("LN-000001")
                .loanType("CASH")
                .interestType("FIXED")
                .amount(new BigDecimal("500000"))
                .repaymentPeriod(60)
                .nominalRate(new BigDecimal("5.5"))
                .effectiveRate(new BigDecimal("6.2"))
                .monthlyPayment(new BigDecimal("9500"))
                .startDate(LocalDate.of(2025, 4, 1))
                .endDate(LocalDate.of(2030, 4, 1))
                .remainingDebt(new BigDecimal("450000"))
                .currency("RSD")
                .status("ACTIVE")
                .accountNumber("222000112345678910")
                .loanPurpose("Renoviranje stana")
                .createdAt(LocalDateTime.of(2025, 3, 20, 10, 0))
                .build();

        testLoanRequest = LoanRequestResponseDto.builder()
                .id(1L)
                .loanType("CASH")
                .interestType("FIXED")
                .amount(new BigDecimal("500000"))
                .currency("RSD")
                .loanPurpose("Renoviranje stana")
                .repaymentPeriod(60)
                .accountNumber("222000112345678910")
                .phoneNumber("+381601234567")
                .employmentStatus("EMPLOYED")
                .monthlyIncome(new BigDecimal("120000"))
                .permanentEmployment(true)
                .employmentPeriod(36)
                .status("PENDING")
                .createdAt(LocalDateTime.of(2025, 3, 20, 10, 0))
                .clientEmail("marko@banka.rs")
                .clientName("Marko Markovic")
                .build();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDownSecurityContext() {
        // Test isolation: ensure we don't leak SecurityContextHolder to next test class.
        SecurityContextHolder.clearContext();
    }

    // ══════════════════════════════════════════════════════════════════
    //  POST /loans
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /loans - 201 Created")
    void applyForLoan_returnsCreated() throws Exception {
        setupSecurityContext("marko@banka.rs");
        when(loanService.createLoanRequest(any(), eq("marko@banka.rs"))).thenReturn(testLoanRequest);

        String payload = """
                {
                  "loanType": "CASH",
                  "interestType": "FIXED",
                  "amount": 500000,
                  "currency": "RSD",
                  "loanPurpose": "Renoviranje stana",
                  "repaymentPeriod": 60,
                  "accountNumber": "222000112345678910"
                }
                """;

        mockMvc.perform(post("/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.loanType").value("CASH"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.clientEmail").value("marko@banka.rs"));

        verify(loanService).createLoanRequest(any(), eq("marko@banka.rs"));
    }

    @Test
    @DisplayName("POST /loans - 400 when validation fails (missing required fields)")
    void applyForLoan_validationFails() throws Exception {
        setupSecurityContext("marko@banka.rs");

        String payload = """
                {
                  "loanType": "CASH"
                }
                """;

        mockMvc.perform(post("/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /loans - 400 when service throws")
    void applyForLoan_serviceThrows() throws Exception {
        setupSecurityContext("marko@banka.rs");
        when(loanService.createLoanRequest(any(), any()))
                .thenThrow(new IllegalArgumentException("Account not found"));

        String payload = """
                {
                  "loanType": "CASH",
                  "interestType": "FIXED",
                  "amount": 500000,
                  "currency": "RSD",
                  "loanPurpose": "Test",
                  "repaymentPeriod": 60,
                  "accountNumber": "222000112345678910"
                }
                """;

        mockMvc.perform(post("/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    // ══════════════════════════════════════════════════════════════════
    //  GET /loans/my
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /loans/my - 200 OK with loans")
    void getMyLoans_returnsPage() throws Exception {
        setupSecurityContext("marko@banka.rs");

        // Page mora da nosi konkretan Pageable (kao u produkciji preko @PageableDefault),
        // jer Spring Data Jackson PageModule (Spring Boot 4 / Jackson 3) baca gresku pri
        // serijalizaciji Pageable.unpaged() Page-a. Produkcija uvek prosledi pravi Pageable.
        Page<LoanResponseDto> page = new PageImpl<>(List.of(testLoan), PageRequest.of(0, 20), 1);
        when(loanService.getMyLoans(eq("marko@banka.rs"), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/loans/my"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].loanNumber").value("LN-000001"))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /loans/my - 200 OK empty")
    void getMyLoans_emptyPage() throws Exception {
        setupSecurityContext("marko@banka.rs");

        Page<LoanResponseDto> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(loanService.getMyLoans(eq("marko@banka.rs"), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/loans/my"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    // ══════════════════════════════════════════════════════════════════
    //  GET /loans/{id}
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /loans/1 - 200 OK")
    void getLoanById_returnsOk() throws Exception {
        when(loanService.getLoanById(1L)).thenReturn(testLoan);

        mockMvc.perform(get("/loans/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.loanNumber").value("LN-000001"))
                .andExpect(jsonPath("$.amount").value(500000));

        verify(loanService).getLoanById(1L);
    }

    @Test
    @DisplayName("GET /loans/999 - 400 when not found")
    void getLoanById_notFound() throws Exception {
        when(loanService.getLoanById(999L))
                .thenThrow(new IllegalArgumentException("Loan not found"));

        mockMvc.perform(get("/loans/999"))
                .andExpect(status().isBadRequest());
    }

    // ══════════════════════════════════════════════════════════════════
    //  GET /loans/{id}/installments
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /loans/1/installments - 200 OK")
    void getInstallments_returnsOk() throws Exception {
        InstallmentResponseDto installment = InstallmentResponseDto.builder()
                .id(1L)
                .amount(new BigDecimal("9500"))
                .interestRate(new BigDecimal("5.5"))
                .currency("RSD")
                .expectedDueDate(LocalDate.of(2025, 5, 1))
                .actualDueDate(null)
                .paid(false)
                .build();

        when(loanService.getInstallments(1L)).thenReturn(List.of(installment));

        mockMvc.perform(get("/loans/1/installments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].amount").value(9500))
                .andExpect(jsonPath("$[0].paid").value(false));

        verify(loanService).getInstallments(1L);
    }

    @Test
    @DisplayName("GET /loans/999/installments - 400 when loan not found")
    void getInstallments_loanNotFound() throws Exception {
        when(loanService.getInstallments(999L))
                .thenThrow(new IllegalArgumentException("Loan not found"));

        mockMvc.perform(get("/loans/999/installments"))
                .andExpect(status().isBadRequest());
    }

    // ══════════════════════════════════════════════════════════════════
    //  GET /loans/requests
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /loans/requests - 200 OK all requests")
    void getLoanRequests_returnsPage() throws Exception {
        Page<LoanRequestResponseDto> page = new PageImpl<>(List.of(testLoanRequest), PageRequest.of(0, 20), 1);
        when(loanService.getLoanRequests(any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/loans/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("GET /loans/requests?status=PENDING - 200 OK filtered")
    void getLoanRequests_filteredByStatus() throws Exception {
        Page<LoanRequestResponseDto> page = new PageImpl<>(List.of(testLoanRequest), PageRequest.of(0, 20), 1);
        when(loanService.getLoanRequests(eq(LoanStatus.PENDING), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/loans/requests")
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    // ══════════════════════════════════════════════════════════════════
    //  PATCH /loans/requests/{id}/approve
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PATCH /loans/requests/1/approve - 200 OK")
    void approveLoan_returnsOk() throws Exception {
        when(loanService.approveLoanRequest(1L)).thenReturn(testLoan);

        mockMvc.perform(patch("/loans/requests/1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(loanService).approveLoanRequest(1L);
    }

    @Test
    @DisplayName("PATCH /loans/requests/999/approve - 400 when not found")
    void approveLoan_notFound() throws Exception {
        when(loanService.approveLoanRequest(999L))
                .thenThrow(new IllegalArgumentException("Loan request not found"));

        mockMvc.perform(patch("/loans/requests/999/approve"))
                .andExpect(status().isBadRequest());
    }

    // ══════════════════════════════════════════════════════════════════
    //  PATCH /loans/requests/{id}/reject
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PATCH /loans/requests/1/reject - 200 OK")
    void rejectLoan_returnsOk() throws Exception {
        LoanRequestResponseDto rejected = LoanRequestResponseDto.builder()
                .id(1L).loanType("CASH").status("REJECTED").build();
        when(loanService.rejectLoanRequest(1L)).thenReturn(rejected);

        mockMvc.perform(patch("/loans/requests/1/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        verify(loanService).rejectLoanRequest(1L);
    }

    @Test
    @DisplayName("PATCH /loans/requests/999/reject - 400 when not found")
    void rejectLoan_notFound() throws Exception {
        when(loanService.rejectLoanRequest(999L))
                .thenThrow(new IllegalArgumentException("Loan request not found"));

        mockMvc.perform(patch("/loans/requests/999/reject"))
                .andExpect(status().isBadRequest());
    }

    // ══════════════════════════════════════════════════════════════════
    //  GET /loans
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /loans - 200 OK all loans")
    void getAllLoans_returnsPage() throws Exception {
        Page<LoanResponseDto> page = new PageImpl<>(List.of(testLoan), PageRequest.of(0, 20), 1);
        when(loanService.getAllLoans(any(), any(), any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/loans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].loanNumber").value("LN-000001"));
    }

    @Test
    @DisplayName("GET /loans?loanType=CASH&status=ACTIVE - 200 OK filtered")
    void getAllLoans_filtered() throws Exception {
        Page<LoanResponseDto> page = new PageImpl<>(List.of(testLoan), PageRequest.of(0, 20), 1);
        when(loanService.getAllLoans(eq(LoanType.CASH), eq(LoanStatus.ACTIVE), any(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/loans")
                        .param("loanType", "CASH")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    @DisplayName("GET /loans?accountNumber=222000112345678910 - 200 OK filtered by account")
    void getAllLoans_filteredByAccount() throws Exception {
        Page<LoanResponseDto> page = new PageImpl<>(List.of(testLoan), PageRequest.of(0, 20), 1);
        when(loanService.getAllLoans(any(), any(), eq("222000112345678910"), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/loans")
                        .param("accountNumber", "222000112345678910"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    // ──────────────────────────────────────────────────────────────────
    //  Helper
    // ──────────────────────────────────────────────────────────────────

    private void setupSecurityContext(String email) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(email);
        SecurityContext secCtx = mock(SecurityContext.class);
        when(secCtx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(secCtx);
    }
}
