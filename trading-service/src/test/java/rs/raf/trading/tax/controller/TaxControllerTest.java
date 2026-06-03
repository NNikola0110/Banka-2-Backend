package rs.raf.trading.tax.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.raf.trading.common.TradingGlobalExceptionHandler;
import rs.raf.trading.tax.dto.TaxBreakdownItemDto;
import rs.raf.trading.tax.dto.TaxRecordDto;
import rs.raf.trading.tax.service.TaxService;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test {@link TaxController} — adaptacija monolitnog testa (faza 2c).
 * Exception handling kroz {@link TradingGlobalExceptionHandler}; telo greske je
 * {@code {"message": ...}}. Dodato pokrivanje P2.4 breakdown endpointa
 * ({@code GET /tax/{userId}/{userType}/breakdown}, {@code GET /tax/my/breakdown}).
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TaxControllerTest {

    private MockMvc mockMvc;
    @Mock
    private TaxService taxService;

    @Mock
    private rs.raf.trading.audit.service.AuditLogService auditLogService;

    @Mock
    private rs.raf.trading.client.BankaCoreClient bankaCoreClient;

    @InjectMocks
    private TaxController taxController;

    private TaxRecordDto testRecord;
    private TaxRecordDto testRecord2;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(taxController)
                .setControllerAdvice(new TradingGlobalExceptionHandler())
                .build();

        testRecord = new TaxRecordDto(
                1L, 10L, "Marko Markovic", "CLIENT",
                new BigDecimal("50000.0000"),
                new BigDecimal("7500.0000"),
                new BigDecimal("2000.0000"),
                "RSD"
        );

        testRecord2 = new TaxRecordDto(
                2L, 20L, "Ana Jovanovic", "EMPLOYEE",
                new BigDecimal("120000.0000"),
                new BigDecimal("18000.0000"),
                new BigDecimal("5000.0000"),
                "RSD"
        );
    }

    // ══════════════════════════════════════════════════════════════════
    //  GET /tax
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /tax - 200 OK with all records")
    void getTaxRecords_returnsAll() throws Exception {
        when(taxService.getTaxRecords(null, null)).thenReturn(List.of(testRecord, testRecord2));

        mockMvc.perform(get("/tax")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].userName").value("Marko Markovic"))
                .andExpect(jsonPath("$[0].userType").value("CLIENT"))
                .andExpect(jsonPath("$[0].totalProfit").value(50000.0))
                .andExpect(jsonPath("$[0].taxOwed").value(7500.0))
                .andExpect(jsonPath("$[1].userName").value("Ana Jovanovic"))
                .andExpect(jsonPath("$[1].userType").value("EMPLOYEE"));

        verify(taxService).getTaxRecords(null, null);
    }

    @Test
    @DisplayName("GET /tax?userType=CLIENT - 200 OK filtered by userType")
    void getTaxRecords_filteredByUserType() throws Exception {
        when(taxService.getTaxRecords(null, "CLIENT")).thenReturn(List.of(testRecord));

        mockMvc.perform(get("/tax")
                        .param("userType", "CLIENT")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].userType").value("CLIENT"));

        verify(taxService).getTaxRecords(null, "CLIENT");
    }

    @Test
    @DisplayName("GET /tax?name=Marko - 200 OK filtered by name")
    void getTaxRecords_filteredByName() throws Exception {
        when(taxService.getTaxRecords("Marko", null)).thenReturn(List.of(testRecord));

        mockMvc.perform(get("/tax")
                        .param("name", "Marko")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].userName").value("Marko Markovic"));

        verify(taxService).getTaxRecords("Marko", null);
    }

    @Test
    @DisplayName("GET /tax?name=Marko&userType=CLIENT - 200 OK filtered by both")
    void getTaxRecords_filteredByBoth() throws Exception {
        when(taxService.getTaxRecords("Marko", "CLIENT")).thenReturn(List.of(testRecord));

        mockMvc.perform(get("/tax")
                        .param("name", "Marko")
                        .param("userType", "CLIENT")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        verify(taxService).getTaxRecords("Marko", "CLIENT");
    }

    @Test
    @DisplayName("GET /tax - 200 OK with empty list")
    void getTaxRecords_empty() throws Exception {
        when(taxService.getTaxRecords(null, null)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/tax")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ══════════════════════════════════════════════════════════════════
    //  GET /tax/my
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /tax/my - 200 OK with user's tax record")
    void getMyTaxRecord_returnsRecord() throws Exception {
        when(taxService.getMyTaxRecord("marko@banka.rs")).thenReturn(testRecord);

        mockMvc.perform(get("/tax/my")
                        .principal(createAuth("marko@banka.rs"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.userId").value(10))
                .andExpect(jsonPath("$.userName").value("Marko Markovic"))
                .andExpect(jsonPath("$.userType").value("CLIENT"))
                .andExpect(jsonPath("$.totalProfit").value(50000.0))
                .andExpect(jsonPath("$.taxOwed").value(7500.0))
                .andExpect(jsonPath("$.taxPaid").value(2000.0))
                .andExpect(jsonPath("$.currency").value("RSD"));

        verify(taxService).getMyTaxRecord("marko@banka.rs");
    }

    @Test
    @DisplayName("GET /tax/my - 200 OK with empty record for new user")
    void getMyTaxRecord_emptyForNewUser() throws Exception {
        TaxRecordDto emptyRecord = new TaxRecordDto(
                null, 0L, "Nepoznat", "CLIENT",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "RSD"
        );
        when(taxService.getMyTaxRecord("novi@banka.rs")).thenReturn(emptyRecord);

        mockMvc.perform(get("/tax/my")
                        .principal(createAuth("novi@banka.rs"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProfit").value(0))
                .andExpect(jsonPath("$.taxOwed").value(0))
                .andExpect(jsonPath("$.taxPaid").value(0));
    }

    @Test
    @DisplayName("GET /tax/my - 200 OK for employee user")
    void getMyTaxRecord_employeeUser() throws Exception {
        when(taxService.getMyTaxRecord("ana@banka.rs")).thenReturn(testRecord2);

        mockMvc.perform(get("/tax/my")
                        .principal(createAuth("ana@banka.rs"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userType").value("EMPLOYEE"))
                .andExpect(jsonPath("$.userName").value("Ana Jovanovic"));
    }

    // ══════════════════════════════════════════════════════════════════
    //  POST /tax/calculate
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /tax/calculate - 200 OK triggers calculation")
    void triggerCalculation_returnsOk() throws Exception {
        doNothing().when(taxService).calculateTaxForAllUsers();

        mockMvc.perform(post("/tax/calculate")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(taxService).calculateTaxForAllUsers();
    }

    @Test
    @DisplayName("POST /tax/calculate - 400 when service throws (hard non-FX failure still propagates)")
    void triggerCalculation_serviceThrows() throws Exception {
        doThrow(new RuntimeException("Calculation failed"))
                .when(taxService).calculateTaxForAllUsers();

        mockMvc.perform(post("/tax/calculate")
                        .principal(createAuth("admin@banka.rs"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Calculation failed"));

        // R1 429: hard (non-TaxCalculation) failure → audit NOT emitted (genuine error,
        // run did not complete in any meaningful way), exception propagates to 400.
        verify(auditLogService, never()).record(anyLong(), anyString(), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("R1 429: POST /tax/calculate - partial FX failure → 200 + audit still emitted")
    void triggerCalculation_partialFxFailure_returns200AndAudits() throws Exception {
        // TaxCalculationException = partial failure (some users computed+persisted, some skipped
        // for missing FX). Must NOT 400; must still write TAX_RUN_TRIGGERED audit.
        doThrow(new rs.raf.trading.tax.service.TaxCalculationException(
                42L, "CLIENT", "FX rate unavailable for USD, cannot compute tax in RSD", null))
                .when(taxService).calculateTaxForAllUsers();
        when(bankaCoreClient.getUserByEmail("admin@banka.rs"))
                .thenReturn(new rs.raf.banka2.contracts.internal.InternalUserDto(
                        7L, "EMPLOYEE", "admin@banka.rs", "Adm", "In", true, null));

        mockMvc.perform(post("/tax/calculate")
                        .principal(createAuth("admin@banka.rs"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("delimicnim")));

        // audit IS emitted (run was triggered) with actorId resolved + PARTIAL note
        verify(auditLogService).record(
                eq(7L), eq("EMPLOYEE"),
                eq(rs.raf.trading.audit.model.AuditActionType.TAX_RUN_TRIGGERED),
                org.mockito.ArgumentMatchers.contains("PARTIAL"),
                any(), any());
    }

    @Test
    @DisplayName("R1 429: POST /tax/calculate - full success → 200 + audit emitted")
    void triggerCalculation_fullSuccess_returns200AndAudits() throws Exception {
        doNothing().when(taxService).calculateTaxForAllUsers();
        when(bankaCoreClient.getUserByEmail("admin@banka.rs"))
                .thenReturn(new rs.raf.banka2.contracts.internal.InternalUserDto(
                        7L, "EMPLOYEE", "admin@banka.rs", "Adm", "In", true, null));

        mockMvc.perform(post("/tax/calculate")
                        .principal(createAuth("admin@banka.rs"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(auditLogService).record(
                eq(7L), eq("EMPLOYEE"),
                eq(rs.raf.trading.audit.model.AuditActionType.TAX_RUN_TRIGGERED),
                anyString(), any(), any());
    }

    // ══════════════════════════════════════════════════════════════════
    //  OT-1064 (TEST-tr-tax-actuary-exchange-1) — audit best-effort + actor fallback.
    //  triggerCalculation NE sme da pretvori pad audit-a / pad actor-lookup-a u
    //  gresku: tax-run je VEC izvrsen (calculateTaxForAllUsers persistuje per-user
    //  REQUIRES_NEW), pa odgovor mora ostati 200.
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OT-1064: pad audit log-a NE obara vec-zavrsen tax-run (200, best-effort)")
    void triggerCalculation_auditFailure_stillReturns200() throws Exception {
        // Tax run uspeo; ali auditLogService.record pukne — kontroler mora i dalje 200.
        doNothing().when(taxService).calculateTaxForAllUsers();
        when(bankaCoreClient.getUserByEmail("admin@banka.rs"))
                .thenReturn(new rs.raf.banka2.contracts.internal.InternalUserDto(
                        7L, "EMPLOYEE", "admin@banka.rs", "Adm", "In", true, null));
        doThrow(new RuntimeException("audit DB down"))
                .when(auditLogService).record(anyLong(), anyString(), any(), anyString(), any(), any());

        mockMvc.perform(post("/tax/calculate")
                        .principal(createAuth("admin@banka.rs"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Obracun poreza uspesno zavrsen."));

        // audit JE pokusan (best-effort) — pad se proguta.
        verify(auditLogService).record(anyLong(), anyString(),
                eq(rs.raf.trading.audit.model.AuditActionType.TAX_RUN_TRIGGERED),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("OT-1064: actor lookup pad → audit sa fallback actorId=0, run i dalje 200")
    void triggerCalculation_actorLookupFails_auditsWithFallbackActor() throws Exception {
        // banka-core getUserByEmail pukne → actorId fallback 0; run i dalje uspeo (200)
        // i audit se i dalje emituje (sa actorId=0).
        doNothing().when(taxService).calculateTaxForAllUsers();
        when(bankaCoreClient.getUserByEmail("admin@banka.rs"))
                .thenThrow(new rs.raf.trading.client.BankaCoreClientException(503, "banka-core down"));

        mockMvc.perform(post("/tax/calculate")
                        .principal(createAuth("admin@banka.rs"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Obracun poreza uspesno zavrsen."));

        verify(auditLogService).record(
                eq(0L), eq("EMPLOYEE"),
                eq(rs.raf.trading.audit.model.AuditActionType.TAX_RUN_TRIGGERED),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("OT-1064: null Authentication → actor 'UNKNOWN' email, run 200, audit emitovan")
    void triggerCalculation_nullAuthentication_returns200() throws Exception {
        // Kontroler dozvoljava null Authentication (email='UNKNOWN'); run uspeo → 200.
        doNothing().when(taxService).calculateTaxForAllUsers();
        when(bankaCoreClient.getUserByEmail("UNKNOWN"))
                .thenThrow(new rs.raf.trading.client.BankaCoreClientException(404, "no such user"));

        mockMvc.perform(post("/tax/calculate")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(taxService).calculateTaxForAllUsers();
        verify(auditLogService).record(
                eq(0L), eq("EMPLOYEE"),
                eq(rs.raf.trading.audit.model.AuditActionType.TAX_RUN_TRIGGERED),
                org.mockito.ArgumentMatchers.contains("UNKNOWN"), any(), any());
    }

    // ══════════════════════════════════════════════════════════════════
    //  P2.4 — GET /tax/{userId}/{userType}/breakdown
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /tax/{userId}/{userType}/breakdown - 200 OK with per-listing breakdown")
    void getBreakdown_returnsItems() throws Exception {
        TaxBreakdownItemDto item = new TaxBreakdownItemDto(
                5L, "AAPL", "USD",
                new BigDecimal("100.0000"),
                new BigDecimal("10920.0000"),
                new BigDecimal("1638.0000"));
        when(taxService.getTaxBreakdownForUser(10L, "CLIENT")).thenReturn(List.of(item));

        mockMvc.perform(get("/tax/10/CLIENT/breakdown")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$[0].listingCurrency").value("USD"))
                .andExpect(jsonPath("$[0].taxOwed").value(1638.0));

        verify(taxService).getTaxBreakdownForUser(10L, "CLIENT");
    }

    @Test
    @DisplayName("GET /tax/{userId}/{userType}/breakdown - 200 OK with empty list")
    void getBreakdown_empty() throws Exception {
        when(taxService.getTaxBreakdownForUser(99L, "EMPLOYEE")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/tax/99/EMPLOYEE/breakdown")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ══════════════════════════════════════════════════════════════════
    //  P2.4 — GET /tax/my/breakdown
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /tax/my/breakdown - 200 OK with authenticated user's breakdown")
    void getMyBreakdown_returnsItems() throws Exception {
        // R2-1448: kontroler sada zove jedan single-pass servis poziv getMyTaxBreakdown(email).
        TaxBreakdownItemDto item = new TaxBreakdownItemDto(
                5L, "MSFT", "RSD",
                new BigDecimal("5000.0000"),
                new BigDecimal("5000.0000"),
                new BigDecimal("750.0000"));
        when(taxService.getMyTaxBreakdown("marko@banka.rs")).thenReturn(List.of(item));

        mockMvc.perform(get("/tax/my/breakdown")
                        .principal(createAuth("marko@banka.rs"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ticker").value("MSFT"));

        verify(taxService).getMyTaxBreakdown("marko@banka.rs");
        // R2-1448: vise NE poziva getMyTaxRecord + getTaxBreakdownForUser (3 lookupa).
        verify(taxService, never()).getMyTaxRecord(anyString());
        verify(taxService, never()).getTaxBreakdownForUser(anyLong(), anyString());
    }

    @Test
    @DisplayName("GET /tax/my/breakdown - 200 OK with empty list when user has no tax record")
    void getMyBreakdown_noRecord() throws Exception {
        when(taxService.getMyTaxBreakdown("novi@banka.rs")).thenReturn(java.util.List.of());

        mockMvc.perform(get("/tax/my/breakdown")
                        .principal(createAuth("novi@banka.rs"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        verify(taxService).getMyTaxBreakdown("novi@banka.rs");
        verify(taxService, never()).getTaxBreakdownForUser(anyLong(), anyString());
    }

    // ──────────────────────────────────────────────────────────────────
    //  Helper
    // ──────────────────────────────────────────────────────────────────

    private Authentication createAuth(String email) {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(email);
        return auth;
    }
}
