package rs.raf.trading.investmentfund.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.investmentfund.dto.FundDividendHistoryDto;
import rs.raf.trading.investmentfund.dto.FundStatisticsDto;
import rs.raf.trading.investmentfund.dto.InvestmentFundDtos.*;
import rs.raf.trading.investmentfund.service.FundDividendService;
import rs.raf.trading.investmentfund.service.FundStatisticsService;
import rs.raf.trading.investmentfund.service.InvestmentFundService;
import rs.raf.trading.security.TradingUserResolver;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/funds")
@RequiredArgsConstructor
public class InvestmentFundController {

    private final InvestmentFundService investmentFundService;
    private final FundStatisticsService fundStatisticsService;
    private final FundDividendService fundDividendService;
    private final TradingUserResolver userResolver;

    @GetMapping
    public ResponseEntity<List<InvestmentFundSummaryDto>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) java.math.BigDecimal minContribution,
            @RequestParam(required = false) java.math.BigDecimal maxContribution,
            @RequestParam(required = false) java.math.BigDecimal minFundValue,
            @RequestParam(required = false) java.math.BigDecimal maxFundValue,
            @RequestParam(required = false) java.math.BigDecimal minProfit,
            @RequestParam(required = false) java.math.BigDecimal maxProfit,
            // P2-perf-nplus1-1 (R1 492): BE-side manager filter — zamenjuje FE
            // klijentsko filtriranje cele liste fondova po manageru.
            @RequestParam(required = false) Long managerEmployeeId) {
        return ResponseEntity.ok(investmentFundService.listDiscovery(
                search, sort, direction,
                minContribution, maxContribution,
                minFundValue, maxFundValue,
                minProfit, maxProfit, managerEmployeeId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvestmentFundDetailDto> details(@PathVariable Long id) {
        return ResponseEntity.ok(investmentFundService.getFundDetails(id));
    }

    @GetMapping("/{id}/performance")
    public ResponseEntity<List<FundPerformancePointDto>> performance(
            @PathVariable Long id,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false, defaultValue = "MONTH") Granularity granularity) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusMonths(3);
        return ResponseEntity.ok(investmentFundService.getPerformance(id, effectiveFrom, effectiveTo, granularity));
    }

    @GetMapping("/{id}/transactions")
    public ResponseEntity<List<ClientFundTransactionDto>> transactions(@PathVariable Long id) {
        UserContext current = userResolver.resolveCurrent();
        return ResponseEntity.ok(investmentFundService.listTransactions(id, current.userId(), current.userRole()));
    }

    /**
     * B12 — Statistika fondova (spec Celina 4). Bez {@code @PreAuthorize}:
     * statistike su javne (svi authenticated korisnici), paritet sa Funds Discovery.
     */
    @GetMapping("/{id}/statistics")
    public ResponseEntity<FundStatisticsDto> statistics(@PathVariable Long id) {
        return ResponseEntity.ok(fundStatisticsService.computeStatistics(id));
    }

    /**
     * B11 — Istorija dividendi koje je primio fond (spec Celina 4).
     * Bez {@code @PreAuthorize}: pristup za sve authenticated korisnike, paritet sa
     * Funds Discovery i {@link #statistics(Long)}.
     */
    @GetMapping("/{id}/dividends")
    public ResponseEntity<List<FundDividendHistoryDto>> getFundDividendHistory(@PathVariable Long id) {
        return ResponseEntity.ok(fundDividendService.getFundDividendHistory(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ADMIN', 'SUPERVISOR')")
    public ResponseEntity<InvestmentFundDetailDto> create(@Valid @RequestBody CreateFundDto dto) {
        UserContext current = userResolver.resolveCurrent();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(investmentFundService.createFund(dto, current.userId()));
    }

    @PostMapping("/{id}/invest")
    public ResponseEntity<ClientFundPositionDto> invest(
            @PathVariable Long id, @Valid @RequestBody InvestFundDto dto) {
        UserContext current = userResolver.resolveCurrent();
        return ResponseEntity.ok(investmentFundService.invest(id, dto, current.userId(), current.userRole()));
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<ClientFundTransactionDto> withdraw(
            @PathVariable Long id, @Valid @RequestBody WithdrawFundDto dto) {
        UserContext current = userResolver.resolveCurrent();
        return ResponseEntity.ok(investmentFundService.withdraw(id, dto, current.userId(), current.userRole()));
    }

    @GetMapping("/my-positions")
    public ResponseEntity<List<ClientFundPositionDto>> myPositions() {
        UserContext current = userResolver.resolveCurrent();
        return ResponseEntity.ok(investmentFundService.listMyPositions(current.userId(), current.userRole()));
    }

    @GetMapping("/bank-positions")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ADMIN', 'SUPERVISOR')")
    public ResponseEntity<List<ClientFundPositionDto>> bankPositions() {
        return ResponseEntity.ok(investmentFundService.listBankPositions());
    }

    /**
     * Ad-hoc prebacivanje vlasnistva fonda na drugog supervizora.
     * Samo admin (Celina 4 §324: "samo admini mogu da dodaju i uklanjaju
     * permisije isAgent, isSupervisor"). Bulk varijanta (kad admin oduzme
     * isSupervisor permisiju) se i dalje desava automatski kroz
     * {@code EmployeeServiceImpl.updateEmployee}.
     */
    @PostMapping("/{id}/reassign-manager")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ADMIN')")
    public ResponseEntity<InvestmentFundDetailDto> reassignManager(
            @PathVariable Long id,
            @Valid @RequestBody ReassignFundManagerDto dto) {
        return ResponseEntity.ok(
                investmentFundService.reassignSingleFundManager(id, dto.getNewManagerEmployeeId()));
    }

    /**
     * TODO_final C4 #14 / Sc 70: politika obrade dividendi za fond.
     *
     * <p>Authorization: ADMIN ili SUPERVISOR. Service dodatno proverava da je
     * non-admin supervizor stvarno manager ovog fonda.
     *
     * @param id fund id
     * @param dto {@link UpdateDividendPolicyDto} sa {@code reinvest: boolean}
     * @return azurirani fund detail
     */
    @PatchMapping("/{id}/dividend-policy")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ADMIN', 'SUPERVISOR')")
    public ResponseEntity<InvestmentFundDetailDto> updateDividendPolicy(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDividendPolicyDto dto) {
        UserContext current = userResolver.resolveCurrent();
        boolean isAdmin = hasAdminAuthority();
        return ResponseEntity.ok(
                investmentFundService.updateDividendPolicy(id, dto.getReinvest(), current.userId(), isAdmin));
    }

    /**
     * Helper: proverava da li trenutni pozivac ima ADMIN authority. Ne mozemo
     * koristiti {@code @PreAuthorize} samo za ADMIN jer endpoint dozvoljava i
     * SUPERVISOR-a — service treba da zna da li je pozivac admin (override fund
     * manager check) ili supervizor (mora biti manager fonda).
     */
    private boolean hasAdminAuthority() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) return false;
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority() == null ? "" : a.getAuthority())
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ADMIN"));
    }
}
