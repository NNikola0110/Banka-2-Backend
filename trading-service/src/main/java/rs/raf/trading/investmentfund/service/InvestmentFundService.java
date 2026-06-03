package rs.raf.trading.investmentfund.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.service.AuditLogService;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserRole;
import jakarta.persistence.EntityNotFoundException;
import rs.raf.trading.investmentfund.dto.InvestmentFundDtos.*;
import rs.raf.trading.investmentfund.mapper.InvestmentFundMapper;
import rs.raf.trading.investmentfund.model.*;
import rs.raf.trading.investmentfund.repository.*;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
 * NAPOMENA (copy-first ekstrakcija, faza 2c — money-seam rewiring):
 * monolitna verzija je direktno menjala Account.balance/availableBalance
 * (privatni debit/credit/creditBankFxCommission helperi) i kreirala fond
 * racun preko AccountRepository. U trading-service-u racuni zive u banka-core
 * domenu, pa novcane operacije idu kroz banka-core interni settlement seam:
 *   - createFund    -> POST /internal/accounts/fund (provisionFundAccount)
 *   - invest        -> POST /internal/funds/transfer (sourceAccount -> fundAccount)
 *   - withdraw/payout-> POST /internal/funds/transfer (fundAccount -> destination)
 * Identitet (klijent/zaposleni ime, email->id) razresava se preko banka-core
 * internog /internal/users API-ja (TradingUserResolver / BankaCoreClient).
 * Likvidacijska grana (FundLiquidationService) i FUND orderi su lokalni
 * (trading entiteti). Idempotency kljucevi su deterministicki po
 * ClientFundTransaction id-u — retry replay-uje umesto da dvaput naplati.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvestmentFundService {

    private static final String RSD = "RSD";
    // P2-profit-fx-fee-1 (R5 1877): jedinstven izvor istine za FX fee (1%, nepromenjeno).
    private static final BigDecimal FX_FEE_RATE = rs.raf.trading.common.FxFeePolicy.FX_FEE_RATE;
    private static final int MONEY_SCALE = 4;

    private final FundValueSnapshotRepository fundValueSnapshotRepository;
    private final InvestmentFundRepository investmentFundRepository;
    private final ClientFundPositionRepository clientFundPositionRepository;
    private final ClientFundTransactionRepository clientFundTransactionRepository;
    private final BankaCoreClient bankaCoreClient;
    private final TradingUserResolver tradingUserResolver;
    private final ActuaryInfoRepository actuaryInfoRepository;
    private final PortfolioRepository portfolioRepository;
    private final ListingRepository listingRepository;
    private final FundValueCalculator fundValueCalculator;
    private final FundLiquidationService fundLiquidationService;
    private final CurrencyConversionService currencyConversionService;
    private final rs.raf.trading.investmentfund.scheduler.FundValueSnapshotScheduler fundValueSnapshotScheduler;
    // P2-audit-coverage-1 (R5 1888): audit create/invest/withdraw (Celina 4 money/lifecycle).
    private final AuditLogService auditLogService;
    // C-notif-email (02.06): pravo obavestenje klijentu pri isplati/likvidaciji iz
    // fonda (Sc36 — "klijent dobija obavestenje da ce isplatu dobiti u kratkom roku").
    private final rs.raf.trading.notification.service.NotificationService notificationService;

    /**
     * T12 — fallback strategija za "Banka kao klijent fonda" (Celina 4 (Nova) §4406-4435).
     *
     * Email pod kojim seed.sql kreira "Banka 2 d.o.o." klijenta. Po default-u
     * "banka2.doo@banka.rs" (vidi application.properties + seed.sql).
     * InvestmentFundService.listBankPositions koristi ga da resolvuje
     * `clients.id` u runtime (jer client_id je auto-generisan i ne mozemo ga
     * forsirati na konstantu).
     */
    @Value("${bank.owner-client-email:banka2.doo@banka.rs}")
    private String bankOwnerClientEmail;

    @Transactional
    public InvestmentFundDetailDto createFund(CreateFundDto dto, Long supervisorId) {
        if (investmentFundRepository.findByName(dto.getName()).isPresent()) {
            throw new IllegalArgumentException("Fund with name '" + dto.getName() + "' already exists.");
        }

        actuaryInfoRepository.findByEmployeeId(supervisorId)
                .filter(a -> a.getActuaryType() == ActuaryType.SUPERVISOR)
                .orElseThrow(() -> new IllegalStateException("Only supervisors can create funds."));

        // Provizioniranje fond racuna: monolit je gradio pun Account
        // (BUSINESS / FUND kategorija, RSD, bankina firma, broj racuna).
        // U trading-service-u racuni zive u banka-core domenu — ta logika je
        // preseljena u banka-core POST /internal/accounts/fund.
        InternalAccountDto fundAccount = bankaCoreClient.provisionFundAccount(dto.getName(), supervisorId);

        InvestmentFund fund = new InvestmentFund();
        fund.setName(dto.getName());
        fund.setDescription(dto.getDescription());
        fund.setMinimumContribution(dto.getMinimumContribution());
        fund.setManagerEmployeeId(supervisorId);
        fund.setAccountId(fundAccount.id());
        fund.setCreatedAt(LocalDateTime.now());
        fund.setInceptionDate(LocalDate.now());
        fund.setActive(true);
        // TODO_final C4 #14 / Sc 70: politika dividendi (default false = distribute klijentima).
        fund.setReinvestDividends(Boolean.TRUE.equals(dto.getReinvestDividends()));
        fund = investmentFundRepository.save(fund);

        FundValueSnapshot initialSnapshot = new FundValueSnapshot();
        initialSnapshot.setFundId(fund.getId());
        initialSnapshot.setSnapshotDate(LocalDate.now());
        initialSnapshot.setFundValue(BigDecimal.ZERO);
        initialSnapshot.setLiquidAmount(BigDecimal.ZERO);
        initialSnapshot.setInvestedTotal(BigDecimal.ZERO);
        initialSnapshot.setProfit(BigDecimal.ZERO);
        fundValueSnapshotRepository.save(initialSnapshot);

        String managerName = resolveUserName(supervisorId, UserRole.EMPLOYEE);
        log.info("Fund '{}' created, account #{}", fund.getName(), fundAccount.id());

        // R5 1888 (P2-audit-coverage-1): audit kreiranja fonda. afterCommit + best-effort —
        // pise se SAMO ako createFund tx commit-uje (nema phantom audit na rollback fond-racuna)
        // i pad audit-a NE obara kreiranje. Aktor = supervizor.
        auditLogService.recordAfterCommit(
                supervisorId, "EMPLOYEE",
                AuditActionType.FUND_CREATED,
                "Fund '" + fund.getName() + "' created (id=" + fund.getId()
                        + ", account=" + fundAccount.id() + ", minContribution="
                        + fund.getMinimumContribution() + ")",
                "FUND", fund.getId());

        return InvestmentFundMapper.toDetailDto(fund, fundAccount, BigDecimal.ZERO, BigDecimal.ZERO,
                Collections.emptyList(), Collections.emptyList(),
                managerName != null ? managerName : "N/A");
    }

    public List<InvestmentFundSummaryDto> listDiscovery(String searchQuery, String sortField, String sortDirection) {
        return listDiscovery(searchQuery, sortField, sortDirection,
                null, null, null, null, null, null);
    }

    public List<InvestmentFundSummaryDto> listDiscovery(String searchQuery, String sortField, String sortDirection,
                                                         BigDecimal minContribution, BigDecimal maxContribution,
                                                         BigDecimal minFundValue, BigDecimal maxFundValue,
                                                         BigDecimal minProfit, BigDecimal maxProfit) {
        return listDiscovery(searchQuery, sortField, sortDirection,
                minContribution, maxContribution, minFundValue, maxFundValue,
                minProfit, maxProfit, null);
    }

    /**
     * P2-perf-nplus1-1 (R1 492): BE-side filter po {@code managerEmployeeId}.
     * Ranije je FE dovlacio CELU listu fondova pa filtrirao po manageru
     * klijentski (N+1 po manager-prikazu) — sad se filter primenjuje na BE-u
     * (DB-side findByManagerEmployeeId kad je manager poznat, inace pun aktivni
     * set sa ostalim filterima). Aditivno; postojeci pozivaoci ne menjaju se.
     */
    public List<InvestmentFundSummaryDto> listDiscovery(String searchQuery, String sortField, String sortDirection,
                                                         BigDecimal minContribution, BigDecimal maxContribution,
                                                         BigDecimal minFundValue, BigDecimal maxFundValue,
                                                         BigDecimal minProfit, BigDecimal maxProfit,
                                                         Long managerEmployeeId) {
        List<InvestmentFund> funds = managerEmployeeId != null
                ? investmentFundRepository.findByManagerEmployeeId(managerEmployeeId).stream()
                        .filter(InvestmentFund::isActive)
                        .sorted(Comparator.comparing(InvestmentFund::getName,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList()
                : investmentFundRepository.findByActiveTrueOrderByNameAsc();

        Stream<InvestmentFund> stream = funds.stream();
        if (searchQuery != null && !searchQuery.isBlank()) {
            String q = searchQuery.toLowerCase();
            stream = stream.filter(f ->
                    (f.getName() != null && f.getName().toLowerCase().contains(q)) ||
                            (f.getDescription() != null && f.getDescription().toLowerCase().contains(q)));
        }
        if (minContribution != null) {
            stream = stream.filter(f -> f.getMinimumContribution() != null
                    && f.getMinimumContribution().compareTo(minContribution) >= 0);
        }
        if (maxContribution != null) {
            stream = stream.filter(f -> f.getMinimumContribution() != null
                    && f.getMinimumContribution().compareTo(maxContribution) <= 0);
        }

        List<InvestmentFundSummaryDto> result = stream.map(f -> {
            BigDecimal fundValue = safeCompute(() -> fundValueCalculator.computeFundValue(f), BigDecimal.ZERO);
            BigDecimal profit = safeCompute(() -> fundValueCalculator.computeProfit(f), BigDecimal.ZERO);
            String managerName = Optional.ofNullable(resolveUserName(f.getManagerEmployeeId(), UserRole.EMPLOYEE))
                    .orElse("N/A");
            return InvestmentFundMapper.toSummaryDto(f, fundValue, profit, managerName);
        }).collect(Collectors.toCollection(ArrayList::new));

        // Numericki filteri po izvedenim poljima primenjeni nakon mapiranja (DTO ima vrednosti).
        if (minFundValue != null) {
            result.removeIf(d -> d.getFundValue() == null || d.getFundValue().compareTo(minFundValue) < 0);
        }
        if (maxFundValue != null) {
            result.removeIf(d -> d.getFundValue() == null || d.getFundValue().compareTo(maxFundValue) > 0);
        }
        if (minProfit != null) {
            result.removeIf(d -> d.getProfit() == null || d.getProfit().compareTo(minProfit) < 0);
        }
        if (maxProfit != null) {
            result.removeIf(d -> d.getProfit() == null || d.getProfit().compareTo(maxProfit) > 0);
        }

        Comparator<InvestmentFundSummaryDto> comparator = buildComparator(sortField);
        if ("DESC".equalsIgnoreCase(sortDirection)) {
            comparator = comparator.reversed();
        }
        result.sort(comparator);
        return result;
    }

    public InvestmentFundDetailDto getFundDetails(Long fundId) {
        InvestmentFund fund = investmentFundRepository.findById(fundId)
                .orElseThrow(() -> new EntityNotFoundException("Fund #" + fundId + " not found."));

        InternalAccountDto account = getFundAccountOrThrow(fund.getAccountId());

        BigDecimal fundValue = safeCompute(() -> fundValueCalculator.computeFundValue(fund), BigDecimal.ZERO);
        BigDecimal profit = safeCompute(() -> fundValueCalculator.computeProfit(fund), BigDecimal.ZERO);

        List<Portfolio> portfolios = portfolioRepository.findByUserIdAndUserRole(fund.getId(), UserRole.FUND);
        List<FundHoldingDto> holdings = portfolios.stream()
                .filter(p -> p.getQuantity() != null && p.getQuantity() > 0)
                .map(p -> {
                    Listing listing = listingRepository.findById(p.getListingId()).orElse(null);
                    BigDecimal currentPrice = listing != null ? listing.getPrice() : BigDecimal.ZERO;
                    BigDecimal change = listing != null ? listing.getPriceChange() : BigDecimal.ZERO;
                    Long volume = listing != null ? listing.getVolume() : 0L;
                    return new FundHoldingDto(
                            p.getListingId(),
                            p.getListingTicker(),
                            p.getListingName(),
                            p.getQuantity(),
                            currentPrice,
                            change,
                            volume,
                            p.getAverageBuyPrice(),
                            p.getLastModified() != null ? p.getLastModified().toLocalDate() : null);
                })
                .toList();

        LocalDate from = LocalDate.now().minusDays(30);
        LocalDate to = LocalDate.now();
        List<FundPerformancePointDto> performance = fundValueSnapshotRepository
                .findByFundIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(fund.getId(), from, to)
                .stream()
                .map(s -> new FundPerformancePointDto(s.getSnapshotDate(), s.getFundValue(), s.getProfit()))
                .toList();

        String managerName = Optional.ofNullable(resolveUserName(fund.getManagerEmployeeId(), UserRole.EMPLOYEE))
                .orElse("N/A");

        return InvestmentFundMapper.toDetailDto(fund, account, fundValue, profit, holdings, performance, managerName);
    }

    /**
     * P11 — Spec Celina 4 (Nova) §3592-3629: "Performanse fonda: tabela ili
     * grafikon (mesecni, kvartalni ili godisnji prikaz)".
     *
     * IMPLEMENTIRANO. FundValueSnapshot tabela snima dnevno
     * (FundValueSnapshotScheduler 23:45); ovde agregiramo po granularity:
     *  - DAY    -> sve tacke izmedju [from, to]
     *  - WEEK   -> grupisi po ISO sedmici, uzmi poslednju vrednost
     *  - MONTH  -> grupisi po YYYY-MM, uzmi poslednju vrednost
     *  - QUARTER-> grupisi po (YYYY, ceil(month/3)), poslednja vrednost
     *  - YEAR   -> grupisi po YYYY, poslednja vrednost
     * Vraca listu FundPerformancePointDto sortiranu po datumu ASC.
     *
     * FE FundDetailsPage ima toggle Day/Week/Month/Quarter/Year koji se
     * mapira na granularity parametar.
     */
    public List<FundPerformancePointDto> getPerformance(Long fundId, LocalDate from, LocalDate to, Granularity granularity) {
        List<FundValueSnapshot> snapshots = fundValueSnapshotRepository.findByFundIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(fundId, from, to);
        if (snapshots.isEmpty()) return List.of();
        List<FundPerformancePointDto> result = new ArrayList<>();
        switch (granularity) {
            case DAY -> {
                for (FundValueSnapshot snap : snapshots) {
                    result.add(new FundPerformancePointDto(snap.getSnapshotDate(), snap.getFundValue(), snap.getProfit()));
                }
            }
            case WEEK -> {
                Map<String, FundValueSnapshot> lastOfWeek = new LinkedHashMap<>();
                for (FundValueSnapshot snap : snapshots) {
                    String key = snap.getSnapshotDate().getYear() + "-W" + snap.getSnapshotDate().get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                    lastOfWeek.put(key, snap);
                }
                for (FundValueSnapshot snap : lastOfWeek.values()) {
                    result.add(new FundPerformancePointDto(snap.getSnapshotDate(), snap.getFundValue(), snap.getProfit()));
                }
            }
            case MONTH -> {
                Map<String, FundValueSnapshot> lastOfMonth = new LinkedHashMap<>();
                for (FundValueSnapshot snap : snapshots) {
                    String key = snap.getSnapshotDate().getYear() + "-" + snap.getSnapshotDate().getMonthValue();
                    lastOfMonth.put(key, snap);
                }
                for (FundValueSnapshot snap : lastOfMonth.values()) {
                    result.add(new FundPerformancePointDto(snap.getSnapshotDate(), snap.getFundValue(), snap.getProfit()));
                }
            }
            case QUARTER -> {
                Map<String, FundValueSnapshot> lastOfQuarter = new LinkedHashMap<>();
                for (FundValueSnapshot snap : snapshots) {
                    int quarter = (snap.getSnapshotDate().getMonthValue() - 1) / 3 + 1;
                    String key = snap.getSnapshotDate().getYear() + "-Q" + quarter;
                    lastOfQuarter.put(key, snap);
                }
                for (FundValueSnapshot snap : lastOfQuarter.values()) {
                    result.add(new FundPerformancePointDto(snap.getSnapshotDate(), snap.getFundValue(), snap.getProfit()));
                }
            }
            case YEAR -> {
                Map<Integer, FundValueSnapshot> lastOfYear = new LinkedHashMap<>();
                for (FundValueSnapshot snap : snapshots) {
                    int year = snap.getSnapshotDate().getYear();
                    lastOfYear.put(year, snap);
                }
                for (FundValueSnapshot snap : lastOfYear.values()) {
                    result.add(new FundPerformancePointDto(snap.getSnapshotDate(), snap.getFundValue(), snap.getProfit()));
                }
            }
        }
        result.sort(java.util.Comparator.comparing(FundPerformancePointDto::getDate));
        return result;
    }

    /**
     * T8 — Celina 4 (Nova), Investicioni fondovi / ClientFundInvestment.
     *
     * Uplata uvek zavrsava kao RSD priliv na racun fonda. Klijent moze da
     * uplati sa svog racuna, a supervizor uplacuje u ime banke sa bankinog
     * racuna. Banka se u ClientFundPosition modelu vodi kao poseban klijent
     * seed-ovan preko bank.owner-client-email, sto je T12 konvencija.
     */
    @Transactional
    public ClientFundPositionDto invest(Long fundId, InvestFundDto dto, Long userId, String userRole) {
        if (dto == null || dto.getAmount() == null || dto.getAmount().signum() <= 0) {
            throw new IllegalArgumentException("Iznos uplate mora biti pozitivan.");
        }

        InvestmentFund fund = findActiveFund(fundId);
        InternalAccountDto sourceAccount = getAccountOrThrow(dto.getSourceAccountId(),
                "Izvorni racun ne postoji: " + dto.getSourceAccountId());
        InternalAccountDto fundAccount = getAccountOrThrow(fund.getAccountId(),
                "Racun fonda ne postoji: " + fund.getAccountId());

        if (Objects.equals(sourceAccount.id(), fundAccount.id())) {
            throw new IllegalArgumentException("Izvorni racun ne moze biti isti kao racun fonda.");
        }

        String actorRole = normalizeUserRole(userRole);
        ensureAccountCanBeUsed(sourceAccount, userId, actorRole, "uplate");
        InvestorIdentity investor = resolveInvestorIdentity(userId, actorRole);

        InvestmentAmounts amounts = calculateInvestmentAmounts(dto, sourceAccount, actorRole);
        if (amounts.amountRsd().compareTo(nullToZero(fund.getMinimumContribution())) < 0) {
            throw new IllegalArgumentException("Iznos uplate mora biti najmanje "
                    + fund.getMinimumContribution() + " RSD.");
        }

        BigDecimal totalDebit = amounts.debitAmount().add(amounts.fxCommission()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (nullToZero(sourceAccount.availableBalance()).compareTo(totalDebit) < 0) {
            throw new InsufficientFundsException("Nedovoljno sredstava na racunu "
                    + sourceAccount.accountNumber() + ". Potrebno: " + totalDebit + " "
                    + sourceAccount.currencyCode());
        }

        ClientFundTransaction tx = new ClientFundTransaction();
        tx.setFundId(fund.getId());
        tx.setUserId(investor.userId());
        tx.setUserRole(investor.userRole());
        tx.setAmountRsd(amounts.amountRsd());
        tx.setSourceAccountId(sourceAccount.id());
        tx.setInflow(true);
        tx.setStatus(ClientFundTransactionStatus.PENDING);
        tx.setCreatedAt(LocalDateTime.now());
        tx = clientFundTransactionRepository.save(tx);

        // banka-core transfer: izvorni racun -> fond racun. Verno monolitu
        // (InvestmentFundService.invest): izvorni racun gubi debitAmount+fxCommission
        // u SVOJOJ valuti (= totalDebit), fond racun dobija amountRsd u RSD, banka
        // dobija fxCommission u valuti izvornog racuna. Pozivalac je vec uradio FX
        // matematiku (calculateInvestmentAmounts). banka-core transfer baca 409 ako
        // izvorni racun nema dovoljno sredstava. Idempotency kljuc je deterministicki
        // po ClientFundTransaction id-u (tx je perzistiran sa PENDING statusom PRE
        // novcane noge).
        // P1-funds-1 (1553): STABILAN invest idempotency kljuc — izveden iz
        // (investor, fund, source account, totalDebit, dan), NE iz IDENTITY
        // tx.getId() koji NIJE postojan kroz rollback. Dual-write rizik: transfer
        // commit-uje (novac source→fund) pa upsertPosition/save padne → JPA
        // rollback → tx PENDING, pozicija NIJE kreditovana; manualni retry istog
        // dana sa istim parametrima generisao bi nov txId → nov kljuc → drugi
        // transfer prolazi → DUPLI debit. Stabilan kljuc cini banka-core dedup
        // efektivnim: retry istog dana replay-uje umesto da dvaput naplati.
        String investKey = "fund-invest-" + investor.userRole() + "-" + investor.userId()
                + "-" + fund.getId() + "-" + sourceAccount.id()
                + "-" + totalDebit.stripTrailingZeros().toPlainString()
                + "-" + LocalDate.now();
        try {
            bankaCoreClient.transferFunds(
                    investKey,
                    new TransferFundsRequest(
                            sourceAccount.id(), totalDebit,
                            fundAccount.id(), amounts.amountRsd(),
                            amounts.fxCommission(), sourceAccount.currencyCode(),
                            "Uplata u fond '" + fund.getName() + "' — transakcija #" + tx.getId()));
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() == 409) {
                throw new InsufficientFundsException("Nedovoljno sredstava na racunu "
                        + sourceAccount.accountNumber() + ". Potrebno: " + totalDebit + " "
                        + sourceAccount.currencyCode());
            }
            throw ex;
        }

        tx.setStatus(ClientFundTransactionStatus.COMPLETED);
        tx.setCompletedAt(LocalDateTime.now());
        clientFundTransactionRepository.save(tx);

        ClientFundPosition position = upsertPosition(fund.getId(), investor, amounts.amountRsd());
        log.info("T8 invest completed: fund={}, investor={}#{}, amountRsd={}, sourceAccount={}",
                fund.getId(), investor.userRole(), investor.userId(), amounts.amountRsd(), sourceAccount.id());

        // R5 1888 (P2-audit-coverage-1): audit uplate u fond (money-moving). afterCommit +
        // best-effort — pise se SAMO ako invest tx commit-uje (novac vec presao source→fund)
        // i pad audit-a NE obara uplatu. Aktor = investor (klijent ili supervizor u ime banke).
        auditLogService.recordAfterCommit(
                investor.userId(), investor.userRole(),
                AuditActionType.FUND_INVEST,
                "Invested " + amounts.amountRsd() + " RSD into fund '" + fund.getName()
                        + "' (id=" + fund.getId() + ", sourceAccount=" + sourceAccount.id() + ")",
                "FUND", fund.getId());

        // Bag prijavljen 10.05.2026: FundDetailsPage performance graf prazan jer
        // fund_value_snapshots nema red za danas. Posle uspesne uplate, zovem
        // idempotentni helper koji garantuje 1 tacku grafa za novu vrednost.
        fundValueSnapshotScheduler.snapshotFundIfMissing(fund);

        BigDecimal fundValue = safeCompute(() -> fundValueCalculator.computeFundValue(fund), BigDecimal.ZERO);
        BigDecimal sumInvested = clientFundPositionRepository.findByFundId(fund.getId()).stream()
                .map(ClientFundPosition::getTotalInvested)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return toClientFundPositionDto(
                position,
                fund.getName(),
                resolveUserName(investor.userId(), investor.userRole()),
                fundValue,
                sumInvested);
    }

    /**
     * T8 — Celina 4 (Nova), ClientFundRedemption.
     *
     * Isplata se evidentira kao RSD odliv iz fonda. Ako fond ima dovoljno
     * raspolozivog cash-a, novac se isplacuje odmah. Ako nema, transakcija
     * ostaje PENDING i poziva se T9 FundLiquidationService da proda hartije
     * fonda i kasnije kroz onFillCompleted FIFO razresi pending isplate.
     */
    @Transactional
    public ClientFundTransactionDto withdraw(Long fundId, WithdrawFundDto dto, Long userId, String userRole) {
        if (dto == null) {
            throw new IllegalArgumentException("Podaci za isplatu su obavezni.");
        }

        InvestmentFund fund = findActiveFund(fundId);
        InternalAccountDto fundAccount = getAccountOrThrow(fund.getAccountId(),
                "Racun fonda ne postoji: " + fund.getAccountId());
        InternalAccountDto destinationAccount = getAccountOrThrow(dto.getDestinationAccountId(),
                "Racun za isplatu ne postoji: " + dto.getDestinationAccountId());

        if (Objects.equals(destinationAccount.id(), fundAccount.id())) {
            throw new IllegalArgumentException("Racun za isplatu ne moze biti isti kao racun fonda.");
        }

        String actorRole = normalizeUserRole(userRole);
        ensureAccountCanBeUsed(destinationAccount, userId, actorRole, "isplate");
        InvestorIdentity investor = resolveInvestorIdentity(userId, actorRole);

        ClientFundPosition position = clientFundPositionRepository
                .findByFundIdAndUserIdAndUserRole(fund.getId(), investor.userId(), investor.userRole())
                .orElseThrow(() -> new IllegalArgumentException("Nemate poziciju u fondu " + fund.getName() + "."));

        // P1-funds-1 (221/1348): povlacenje se kapira na CURRENT VALUE (NAV udeo),
        // NE na totalInvested (cost basis) — inace klijent ne moze da povuce
        // profit (§259/335-337). currentValue = fundValue * (totalInvested /
        // sumTotalInvested). Withdraw-all (amount==null) vraca currentValue, ne
        // cost basis.
        BigDecimal totalInvested = nullToZero(position.getTotalInvested());
        BigDecimal currentValue = nullToZero(safeCompute(
                () -> fundValueCalculator.computePositionValue(
                        fund.getId(), investor.userId(), investor.userRole()),
                totalInvested));
        // Defensive: ako NAV racun padne / vrati 0 ili null (npr. banka-core
        // nedostupan), ne dozvoljavamo da withdraw-all isplati 0; fallback na
        // cost basis.
        if (currentValue.signum() <= 0) {
            currentValue = totalInvested;
        }

        BigDecimal amountRsd = dto.getAmount() == null
                ? currentValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                : dto.getAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (amountRsd.signum() <= 0) {
            throw new IllegalArgumentException("Iznos isplate mora biti pozitivan.");
        }
        if (currentValue.compareTo(amountRsd) < 0) {
            throw new IllegalArgumentException(
                    "Trazeni iznos je veci od pozicije (trenutne vrednosti) u fondu.");
        }

        // R1 791: parcijalno povlacenje ne sme ostaviti "dust" poziciju ispod
        // minimuma fonda. Ako preostala vrednost (currentValue - amountRsd) padne
        // ispod minimumContribution a NIJE potpuna isplata (remaining > 0), odbij —
        // simetricno invest gate-u (amountRsd >= minimumContribution). Klijent mora
        // ili da ostane na/iznad minimuma ili da povuce celu poziciju.
        BigDecimal remainingValue = currentValue.subtract(amountRsd).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal minContribution = nullToZero(fund.getMinimumContribution());
        if (remainingValue.signum() > 0 && remainingValue.compareTo(minContribution) < 0) {
            throw new IllegalArgumentException(
                    "Parcijalno povlacenje bi ostavilo poziciju (" + remainingValue
                            + " RSD) ispod minimuma fonda (" + minContribution
                            + " RSD). Povucite manji iznos ili celu poziciju.");
        }

        // P1-funds-1 (1343): cost-basis udeo koji se povlaci OVOM transakcijom,
        // proporcionalno (amountRsd / currentValue). Pozicija se NE smanjuje
        // ovde — tek pri stvarnoj isplati (immediate payout ili FIFO
        // onFillCompleted), da klijent ne izgubi udeo ako likvidacija nikad
        // ne uspe.
        BigDecimal investedDelta = currentValue.signum() > 0
                ? totalInvested.multiply(amountRsd).divide(currentValue, MONEY_SCALE, RoundingMode.HALF_UP)
                : amountRsd;
        if (investedDelta.compareTo(totalInvested) > 0) {
            investedDelta = totalInvested;
        }

        ClientFundTransaction tx = new ClientFundTransaction();
        tx.setFundId(fund.getId());
        tx.setUserId(investor.userId());
        tx.setUserRole(investor.userRole());
        tx.setAmountRsd(amountRsd);
        tx.setSourceAccountId(destinationAccount.id());
        tx.setInflow(false);
        tx.setInvestedDelta(investedDelta);
        tx.setStatus(ClientFundTransactionStatus.PENDING);
        tx.setCreatedAt(LocalDateTime.now());
        tx = clientFundTransactionRepository.save(tx);

        BigDecimal availableCash = nullToZero(fundAccount.availableBalance());
        if (availableCash.compareTo(amountRsd) >= 0) {
            executePayout(tx, fundAccount, destinationAccount, actorRole);
            tx = clientFundTransactionRepository.save(tx);
            // P1-funds-1 (1343): pozicija se umanjuje TEK POSLE uspesne isplate.
            decreasePosition(position, investedDelta);
            // C-notif-email blocker #1 (03.06): Sc35/49/50 — klijent koji povlaci iz
            // LIKVIDNOG fonda (immediate payout) MORA dobiti obavestenje o uspesnoj
            // isplati. Ranije je ovo obavestenje slao SAMO FIFO/likvidacioni put
            // (FundLiquidationService.executeTransactionPayout); za uobicajen slucaj
            // (fond ima dovoljno cash-a) klijent NIJE dobijao nista — "exactly once" je
            // bio prekrsen u nuli (0 emisija). Salje se TACNO JEDNOM po izvrsenoj isplati,
            // keyed po txId (blocker #3) → distinktan od "pokrenuta" eventa istog ili
            // druge isplate.
            notifyFundPayout(investor.userId(), investor.userRole(), tx.getId(), fund.getId(),
                    "Isplata iz fonda uspešna",
                    "Isplata iz fonda " + fund.getName() + " u iznosu od " + amountRsd
                            + " RSD je uspešno procesuirana.");
            log.info("T8 withdraw completed immediately: fund={}, investor={}#{}, amountRsd={}, investedDelta={}",
                    fund.getId(), investor.userRole(), investor.userId(), amountRsd, investedDelta);
        } else {
            BigDecimal shortfall = amountRsd.subtract(availableCash).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            tx.setFailureReason("Nedovoljno likvidnih sredstava; pokrenuta automatska likvidacija hartija.");
            tx = clientFundTransactionRepository.save(tx);
            // Sc36 (TestoviCelina4): klijent dobija obavestenje da ce isplatu dobiti
            // u kratkom roku (pokrenuta automatska likvidacija). Email + in-app.
            notifyFundPayout(investor.userId(), investor.userRole(), tx.getId(), fund.getId(),
                    "Isplata iz fonda pokrenuta",
                    "Isplata iz fonda " + fund.getName() + " je primljena i biće završena "
                            + "u kratkom roku, nakon automatske likvidacije hartija.");
            fundLiquidationService.liquidateFor(fund.getId(), shortfall);
            log.info("T8 withdraw pending: fund={}, investor={}#{}, amountRsd={}, shortfall={}",
                    fund.getId(), investor.userRole(), investor.userId(), amountRsd, shortfall);
        }

        // Bag 10.05.2026 — vidi #invest hook iznad (snapshot za danas).
        fundValueSnapshotScheduler.snapshotFundIfMissing(fund);

        // R5 1888 (P2-audit-coverage-1): audit isplate iz fonda (money-moving). afterCommit +
        // best-effort — pad audit-a NE obara isplatu/likvidaciju. Status (immediate/pending) u opisu.
        auditLogService.recordAfterCommit(
                investor.userId(), investor.userRole(),
                AuditActionType.FUND_WITHDRAW,
                "Withdraw " + amountRsd + " RSD from fund '" + fund.getName() + "' (id=" + fund.getId()
                        + ", destinationAccount=" + destinationAccount.id()
                        + ", status=" + tx.getStatus() + ")",
                "FUND", fund.getId());

        return toClientFundTransactionDto(tx, fund.getName());
    }

    public List<ClientFundTransactionDto> listTransactions(Long fundId, Long requesterId, String requesterRole) {
        InvestmentFund fund = investmentFundRepository.findById(fundId)
                .orElseThrow(() -> new EntityNotFoundException("Fund #" + fundId + " not found."));
        String role = normalizeUserRole(requesterRole);
        List<ClientFundTransaction> transactions = clientFundTransactionRepository.findByFundIdOrderByCreatedAtDesc(fundId);

        if (isEmployeeActor(role)) {
            ensureSupervisor(requesterId);
            Map<Long, String> accountNumbers = resolveAccountNumbers(transactions);
            return transactions.stream()
                    .map(tx -> toClientFundTransactionDto(tx, fund.getName(), accountNumbers))
                    .toList();
        }

        if (UserRole.isClient(role)) {
            List<ClientFundTransaction> mine = transactions.stream()
                    .filter(tx -> Objects.equals(tx.getUserId(), requesterId) && UserRole.CLIENT.equals(tx.getUserRole()))
                    .toList();
            Map<Long, String> accountNumbers = resolveAccountNumbers(mine);
            return mine.stream()
                    .map(tx -> toClientFundTransactionDto(tx, fund.getName(), accountNumbers))
                    .toList();
        }

        throw new AccessDeniedException("Nemate pravo pregleda transakcija fonda.");
    }

    /**
     * P2-perf-nplus1-1 (R5 1895): batch-resolve racuna za listu transakcija fonda.
     * Razresava SAMO distinct (non-null) {@code sourceAccountId} → accountNumber
     * (jedan {@code getAccount} po jedinstvenom racunu) umesto per-transaction
     * lookup-a u petlji (N+1 preko mreze, ne-keshovano). Vise transakcija sa istog
     * racuna deli jedan poziv. Greska na pojedinacnom racunu ostaje izolovana
     * (taj racun se izostavi iz mape → accountNumber null, kao ranije).
     */
    private Map<Long, String> resolveAccountNumbers(List<ClientFundTransaction> transactions) {
        Map<Long, String> accountNumbers = new HashMap<>();
        transactions.stream()
                .map(ClientFundTransaction::getSourceAccountId)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(accountId -> {
                    try {
                        accountNumbers.put(accountId, bankaCoreClient.getAccount(accountId).accountNumber());
                    } catch (BankaCoreClientException ex) {
                        log.warn("Racun #{} za transakciju fonda nije razresiv ({}).",
                                accountId, ex.getMessage());
                    }
                });
        return accountNumbers;
    }

    /**
     * T12 — Spec Celina 4 (Nova) "Moj portfolio -> Moji fondovi page".
     *
     * Vraca sve pozicije (ClientFundPosition) za autentifikovanog korisnika
     * sa popunjenim izvedenim poljima (currentValue, percentOfFund, profit, userName).
     *
     * Batch-optimizovano:
     *  - fondovi se ucitavaju jednim findAllById pozivom
     *  - po fundId-ju jednom racuna fundValue + sumTotalInvested (izbegne N+1
     *    u toClientFundPositionDto kada korisnik ima pozicije u vise fondova)
     *  - userName se resolvuje jednom (svi pozicije ovog korisnika dele isti userId/userRole).
     */
    public List<ClientFundPositionDto> listMyPositions(Long userId, String userRole) {
        if (userId == null || userRole == null || userRole.isBlank()) {
            return List.of();
        }
        List<ClientFundPosition> positions =
                clientFundPositionRepository.findByUserIdAndUserRole(userId, userRole);
        if (positions.isEmpty()) {
            return List.of();
        }
        List<Long> fundIds = positions.stream().map(ClientFundPosition::getFundId).distinct().toList();
        Map<Long, InvestmentFund> fundsById = investmentFundRepository.findAllById(fundIds).stream()
                .collect(Collectors.toMap(InvestmentFund::getId, f -> f));

        // Pre-compute fundValue + sumTotalInvested per fundId — kasnije se mapper
        // poziva N puta (po jednom za svaku poziciju) ali bez novih DB poziva.
        Map<Long, BigDecimal> fundValueById = new HashMap<>();
        Map<Long, BigDecimal> sumInvestedById = new HashMap<>();
        for (Long fundId : fundIds) {
            InvestmentFund fund = fundsById.get(fundId);
            if (fund == null) {
                fundValueById.put(fundId, BigDecimal.ZERO);
                sumInvestedById.put(fundId, BigDecimal.ZERO);
                continue;
            }
            fundValueById.put(fundId, safeCompute(() -> fundValueCalculator.computeFundValue(fund), BigDecimal.ZERO));
            BigDecimal sumInvested = clientFundPositionRepository.findByFundId(fundId).stream()
                    .map(ClientFundPosition::getTotalInvested)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            sumInvestedById.put(fundId, sumInvested);
        }

        String userName = resolveUserName(userId, userRole);

        return positions.stream()
                .map(p -> toClientFundPositionDto(
                        p,
                        Optional.ofNullable(fundsById.get(p.getFundId()))
                                .map(InvestmentFund::getName).orElse(null),
                        userName,
                        fundValueById.getOrDefault(p.getFundId(), BigDecimal.ZERO),
                        sumInvestedById.getOrDefault(p.getFundId(), BigDecimal.ZERO)))
                .toList();
    }

    /**
     * T12 — Spec Celina 4 (Nova) §4406-4435 (Napomena 1+2): Banka kao klijent fonda.
     *
     * Vraca sve pozicije gde je vlasnik klijent koji predstavlja banku
     * (userRole='CLIENT', userId == bank.owner-client-id). Koristi se
     * iz Profit Banke portala "Pozicije u fondovima" tab.
     *
     * Resolvovanje banka client_id-ja:
     *  1) lookup u banka-core preko email-a iz `bank.owner-client-email`
     *     property-ja (default "banka2.doo@banka.rs")
     *  2) ako klijent ne postoji — vrati prazan list (Profit Banke FE
     *     renderuje "Banka nema pozicije" placeholder)
     *
     * Razlog za email-based resolvovanje umesto fixed ID-ja: clients.id
     * je AUTO_INCREMENT pa ne mozemo seed-ovati eksplicitan ID bez
     * konflikta. Email je jedinstven (uk_clients_email constraint) i
     * stabilan kroz re-seed.
     */
    public List<ClientFundPositionDto> listBankPositions() {
        Long bankClientId;
        try {
            bankClientId = bankaCoreClient.getUserByEmail(bankOwnerClientEmail).userId();
        } catch (BankaCoreClientException ex) {
            // Banka klijent nije seed-ovan / banka-core vratio gresku — graceful
            // fallback (Profit Banke FE prikazuje "Banka nema pozicije" placeholder
            // umesto greske).
            log.warn("Bank owner client (email={}) not resolvable via banka-core ({}) — "
                            + "returning empty bank positions list.",
                    bankOwnerClientEmail, ex.getMessage());
            return List.of();
        }
        if (bankClientId == null) {
            log.warn("Bank owner client (email={}) not found — returning empty bank positions list.",
                    bankOwnerClientEmail);
            return List.of();
        }
        // userRole je uvek "CLIENT" za bankine pozicije (Napomena 2: "Klijent
        // je klijent koji je vlasnik banke" — banka se ponasa kao obican CLIENT).
        return listMyPositions(bankClientId, "CLIENT");
    }

    /**
     * Mapper iz domena (ClientFundPosition) u FE DTO sa popunjenim izvedenim poljima.
     *
     * Pre-compute pristup: caller (listMyPositions/listBankPositions) racuna
     * fundValue + sumInvested po fundId-ju jednom i prosledjuje ovde, izbegavajuci
     * N+1 kada korisnik ima pozicije u vise fondova.
     *
     * currentValue = fundValue * (position.totalInvested / sumInvested)
     * percentOfFund = position.totalInvested / sumInvested * 100
     * profit = currentValue - position.totalInvested
     *
     * Edge case: ako sumInvested == 0 (svi su povukli ili tek kreiran fond),
     * vraca 0 da izbegne deljenje s nulom.
     */
    private ClientFundPositionDto toClientFundPositionDto(
            ClientFundPosition position,
            String fundName,
            String userName,
            BigDecimal fundValue,
            BigDecimal sumInvested) {
        ClientFundPositionDto dto = new ClientFundPositionDto();
        dto.setId(position.getId());
        dto.setFundId(position.getFundId());
        dto.setFundName(fundName);
        dto.setUserId(position.getUserId());
        dto.setUserRole(position.getUserRole());
        dto.setUserName(userName);
        BigDecimal totalInvested = position.getTotalInvested() != null
                ? position.getTotalInvested()
                : BigDecimal.ZERO;
        dto.setTotalInvested(totalInvested);

        BigDecimal currentValue = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal percentOfFund = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal safeFundValue = fundValue != null ? fundValue : BigDecimal.ZERO;
        BigDecimal safeSumInvested = sumInvested != null ? sumInvested : BigDecimal.ZERO;
        if (safeSumInvested.signum() > 0) {
            currentValue = safeFundValue.multiply(totalInvested)
                    .divide(safeSumInvested, MONEY_SCALE, RoundingMode.HALF_UP);
            percentOfFund = totalInvested.multiply(new BigDecimal("100"))
                    .divide(safeSumInvested, MONEY_SCALE, RoundingMode.HALF_UP);
        }
        dto.setCurrentValue(currentValue);
        dto.setPercentOfFund(percentOfFund);
        dto.setProfit(currentValue.subtract(totalInvested).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        dto.setLastModifiedAt(position.getLastModifiedAt());
        return dto;
    }

    /**
     * Razresava ime + prezime korisnika preko banka-core internog /internal/users
     * API-ja. Rezilijentno — vraca {@code null} ako razresavanje ne uspe.
     */
    private String resolveUserName(Long userId, String userRole) {
        if (userId == null || userRole == null) return null;
        String role;
        if (UserRole.CLIENT.equalsIgnoreCase(userRole)) {
            role = UserRole.CLIENT;
        } else if (UserRole.isEmployee(userRole) || UserRole.FUND.equalsIgnoreCase(userRole)) {
            role = UserRole.EMPLOYEE;
        } else {
            return null;
        }
        String name = tradingUserResolver.resolveName(userId, role);
        if (name == null || name.isBlank() || "Unknown".equals(name)) {
            return null;
        }
        return name.trim();
    }

    private InvestmentFund findActiveFund(Long fundId) {
        InvestmentFund fund = investmentFundRepository.findById(fundId)
                .orElseThrow(() -> new EntityNotFoundException("Fund #" + fundId + " not found."));
        if (!fund.isActive()) {
            // R1 485: neaktivan fond je state-conflict (409), ne authz-denial (403).
            throw new rs.raf.trading.investmentfund.exception.FundInactiveException(
                    "Fond " + fund.getName() + " nije aktivan.");
        }
        return fund;
    }

    private InvestmentAmounts calculateInvestmentAmounts(InvestFundDto dto, InternalAccountDto sourceAccount, String actorRole) {
        String sourceCurrency = sourceAccount.currencyCode().toUpperCase(Locale.ROOT);
        String requestedCurrency = normalizeCurrency(dto.getCurrency(), sourceCurrency);
        BigDecimal inputAmount = dto.getAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        boolean chargeFx = UserRole.isClient(actorRole) && !RSD.equals(sourceCurrency);

        if (RSD.equals(requestedCurrency)) {
            BigDecimal debitAmount;
            BigDecimal commission;
            if (RSD.equals(sourceCurrency)) {
                debitAmount = inputAmount;
                commission = BigDecimal.ZERO;
            } else {
                CurrencyConversionService.ConversionResult conversion = currencyConversionService
                        .convertForPurchase(inputAmount, RSD, sourceCurrency, chargeFx);
                debitAmount = conversion.amount();
                commission = conversion.commission();
            }
            return new InvestmentAmounts(inputAmount, debitAmount, commission);
        }

        if (requestedCurrency.equals(sourceCurrency)) {
            BigDecimal amountRsd = currencyConversionService.convert(inputAmount, sourceCurrency, RSD);
            BigDecimal commission = chargeFx
                    ? inputAmount.multiply(FX_FEE_RATE).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            return new InvestmentAmounts(amountRsd, inputAmount, commission);
        }

        throw new IllegalArgumentException("Valuta uplate mora biti RSD ili valuta izvornog racuna ("
                + sourceCurrency + ").");
    }

    /**
     * R1 789 — NAMERNO odvojeno od {@code FundLiquidationService.executeTransactionPayout}.
     * Iako oba puta isplacuju RSD->valuta racuna kroz isti banka-core transfer sa
     * istim idempotency kljucem ("fund-payout-"+id), FX-provizioni gate i side-effekti
     * se razlikuju: ovde immediate payout (destinationAccount + actorRole prosledjeni,
     * gate = {@code UserRole.isClient(actorRole)}); tamo FIFO payout (destinationAccount
     * fetchovan iz tx.sourceAccountId, gate = {@code ownerClientId != null && CLIENT},
     * plus decreaseClientPosition + push-notif). Konsolidacija bi spregla money-gate
     * logiku dva servisa — zadrzano razdvojeno (low-risk P3).
     */
    private void executePayout(ClientFundTransaction tx, InternalAccountDto fundAccount,
                               InternalAccountDto destinationAccount, String actorRole) {
        BigDecimal amountRsd = tx.getAmountRsd().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (nullToZero(fundAccount.availableBalance()).compareTo(amountRsd) < 0) {
            throw new InsufficientFundsException("Fond nema dovoljno likvidnih RSD sredstava za isplatu.");
        }

        String destinationCurrency = destinationAccount.currencyCode().toUpperCase(Locale.ROOT);
        BigDecimal grossCredit = RSD.equals(destinationCurrency)
                ? amountRsd
                : currencyConversionService.convert(amountRsd, RSD, destinationCurrency);
        BigDecimal fxFee = (!RSD.equals(destinationCurrency) && UserRole.isClient(actorRole))
                ? grossCredit.multiply(FX_FEE_RATE).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal netCredit = grossCredit.subtract(fxFee).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // banka-core transfer: fond racun (RSD) -> racun za isplatu. Verno monolitu
        // (InvestmentFundService.executePayout): fond gubi amountRsd u RSD, racun za
        // isplatu dobija netCredit (= grossCredit - fxFee) u SVOJOJ valuti, banka
        // dobija fxFee u valuti racuna za isplatu. banka-core pise audit Transaction.
        // Idempotency kljuc je deterministicki po ClientFundTransaction id-u.
        bankaCoreClient.transferFunds(
                "fund-payout-" + tx.getId(),
                new TransferFundsRequest(
                        fundAccount.id(), amountRsd,
                        destinationAccount.id(), netCredit,
                        fxFee, destinationCurrency,
                        "Isplata iz fonda — transakcija #" + tx.getId()));

        tx.setStatus(ClientFundTransactionStatus.COMPLETED);
        tx.setCompletedAt(LocalDateTime.now());
        tx.setFailureReason(null);
    }

    private ClientFundPosition upsertPosition(Long fundId, InvestorIdentity investor, BigDecimal amountRsd) {
        ClientFundPosition position = clientFundPositionRepository
                .findByFundIdAndUserIdAndUserRole(fundId, investor.userId(), investor.userRole())
                .orElseGet(() -> {
                    ClientFundPosition p = new ClientFundPosition();
                    p.setFundId(fundId);
                    p.setUserId(investor.userId());
                    p.setUserRole(investor.userRole());
                    p.setTotalInvested(BigDecimal.ZERO);
                    return p;
                });
        position.setTotalInvested(nullToZero(position.getTotalInvested()).add(amountRsd).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        position.setLastModifiedAt(LocalDateTime.now());
        return clientFundPositionRepository.save(position);
    }

    private void decreasePosition(ClientFundPosition position, BigDecimal amountRsd) {
        BigDecimal remaining = nullToZero(position.getTotalInvested()).subtract(amountRsd)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (remaining.signum() <= 0) {
            clientFundPositionRepository.delete(position);
            return;
        }
        position.setTotalInvested(remaining);
        position.setLastModifiedAt(LocalDateTime.now());
        clientFundPositionRepository.save(position);
    }

    /**
     * Validacija racuna za uplatu/isplatu.
     *
     * <p>Verno monolitovom {@code InvestmentFundService.ensureAccountCanBeUsed}:
     * {@link InternalAccountDto} sada nosi {@code ownerClientId}/
     * {@code accountCategory}, pa se monolitova provera vlasnistva reprodukuje:
     * <ul>
     *   <li>racun mora biti ACTIVE;</li>
     *   <li>klijent moze koristiti samo SVOJ racun
     *       ({@code ownerClientId == actorId});</li>
     *   <li>supervizor za uplatu/isplatu u ime banke mora izabrati bankin
     *       {@code BANK_TRADING} racun (company-owned — {@code ownerClientId}
     *       je {@code null}).</li>
     * </ul>
     */
    private void ensureAccountCanBeUsed(InternalAccountDto account, Long actorId, String actorRole, String operationName) {
        if (account.status() == null || !"ACTIVE".equalsIgnoreCase(account.status())) {
            throw new IllegalArgumentException("Racun " + account.accountNumber() + " nije aktivan.");
        }
        if (UserRole.isClient(actorRole)) {
            if (account.ownerClientId() == null || !account.ownerClientId().equals(actorId)) {
                throw new AccessDeniedException("Racun " + account.accountNumber()
                        + " ne pripada ulogovanom klijentu.");
            }
            return;
        }
        if (isEmployeeActor(actorRole)) {
            ensureSupervisor(actorId);
            // BANK_TRADING racun je company-owned (bankina firma) — ownerClientId je null.
            if (account.ownerClientId() != null
                    || !"BANK_TRADING".equalsIgnoreCase(account.accountCategory())) {
                throw new AccessDeniedException("Supervizor za " + operationName
                        + " u ime banke mora izabrati bankin trading racun.");
            }
            return;
        }
        throw new AccessDeniedException("Nepodrzana uloga za operaciju fonda: " + actorRole);
    }

    private InvestorIdentity resolveInvestorIdentity(Long actorId, String actorRole) {
        if (UserRole.isClient(actorRole)) {
            return new InvestorIdentity(actorId, UserRole.CLIENT);
        }
        if (isEmployeeActor(actorRole)) {
            ensureSupervisor(actorId);
            Long bankClientId = resolveBankOwnerClientId();
            return new InvestorIdentity(bankClientId, UserRole.CLIENT);
        }
        throw new AccessDeniedException("Nepodrzana uloga za investicione fondove: " + actorRole);
    }

    private Long resolveBankOwnerClientId() {
        try {
            Long bankClientId = bankaCoreClient.getUserByEmail(bankOwnerClientEmail).userId();
            if (bankClientId == null) {
                throw new IllegalStateException("Banka kao klijent nije seed-ovana: " + bankOwnerClientEmail);
            }
            return bankClientId;
        } catch (BankaCoreClientException ex) {
            throw new IllegalStateException("Banka kao klijent nije seed-ovana: " + bankOwnerClientEmail, ex);
        }
    }

    private void ensureSupervisor(Long employeeId) {
        actuaryInfoRepository.findByEmployeeId(employeeId)
                .filter(a -> a.getActuaryType() == ActuaryType.SUPERVISOR)
                .orElseThrow(() -> new AccessDeniedException("Samo supervizor moze da ulaze/povlaci novac u ime banke."));
    }

    private String normalizeUserRole(String userRole) {
        if (userRole == null || userRole.isBlank()) {
            throw new AccessDeniedException("Uloga korisnika nije poznata.");
        }
        String role = userRole.toUpperCase(Locale.ROOT);
        if (role.startsWith("ROLE_")) {
            role = role.substring("ROLE_".length());
        }
        if (UserRole.ADMIN.equals(role) || UserRole.SUPERVISOR.equals(role)) {
            return UserRole.EMPLOYEE;
        }
        return role;
    }

    private boolean isEmployeeActor(String role) {
        return UserRole.isEmployee(role) || UserRole.isAdmin(role) || UserRole.SUPERVISOR.equals(role);
    }

    private String normalizeCurrency(String currency, String fallback) {
        return (currency == null || currency.isBlank() ? fallback : currency).toUpperCase(Locale.ROOT);
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private ClientFundTransactionDto toClientFundTransactionDto(ClientFundTransaction tx, String fundName) {
        String accountNumber = null;
        if (tx.getSourceAccountId() != null) {
            try {
                accountNumber = bankaCoreClient.getAccount(tx.getSourceAccountId()).accountNumber();
            } catch (BankaCoreClientException ex) {
                log.warn("Racun #{} za transakciju fonda #{} nije razresiv ({}).",
                        tx.getSourceAccountId(), tx.getId(), ex.getMessage());
            }
        }
        return buildDto(tx, fundName, accountNumber);
    }

    /**
     * P2-perf-nplus1-1 (R5 1895): batch-aware varijanta — accountNumber je vec
     * razresen (iz {@link #resolveAccountNumbers} mape), bez per-transaction
     * mreznog lookup-a.
     */
    private ClientFundTransactionDto toClientFundTransactionDto(ClientFundTransaction tx, String fundName,
                                                               Map<Long, String> accountNumbers) {
        String accountNumber = tx.getSourceAccountId() != null
                ? accountNumbers.get(tx.getSourceAccountId())
                : null;
        return buildDto(tx, fundName, accountNumber);
    }

    private ClientFundTransactionDto buildDto(ClientFundTransaction tx, String fundName, String accountNumber) {
        return new ClientFundTransactionDto(
                tx.getId(),
                tx.getFundId(),
                fundName,
                tx.getUserId(),
                resolveDisplayName(tx.getUserId(), tx.getUserRole()),
                tx.getAmountRsd(),
                accountNumber,
                tx.isInflow(),
                tx.getStatus() != null ? tx.getStatus().name() : null,
                tx.getCreatedAt(),
                tx.getCompletedAt(),
                tx.getFailureReason());
    }

    private String resolveDisplayName(Long userId, String userRole) {
        if (UserRole.isClient(userRole)) {
            String name = resolveUserName(userId, UserRole.CLIENT);
            return name != null ? name : "Klijent #" + userId;
        }
        String name = resolveUserName(userId, UserRole.EMPLOYEE);
        return name != null ? name : "Zaposleni #" + userId;
    }

    /**
     * C-notif-email (02.06): salje pravo obavestenje klijentu/supervizoru o isplati
     * iz fonda (email + in-app) preko {@link rs.raf.trading.notification.service.NotificationService},
     * tip {@code FUND_PAYOUT}. Zamenjuje raniji log-only stub (R1 490). Best-effort:
     * greska se loguje i NE obara withdraw transakciju. Spec TestoviCelina4 Sc35/36/49/50.
     *
     * <p><b>C-notif-email blocker #3 (03.06): kljuc po {@code ClientFundTransaction.id}.</b>
     * Referenca je {@code ("FUND_TRANSACTION", txId)} — NE {@code ("FUND", fundId)}.
     * banka-core in-app idempotency kljuc se izvodi iz
     * {@code (recipient, type, referenceType, referenceId)} i ZANEMARUJE title/body
     * kad je referenca non-null. Kad bi referenca bila fundId, dve razlicite isplate
     * iz istog fonda istom klijentu — i "Isplata pokrenuta" (Sc36) + "Isplata uspesna"
     * (Sc35) za ISTU isplatu — generisale bi IDENTICAN kljuc → banka-core bi
     * SUPRESOVAO drugu in-app notifikaciju (bell promasuje legitiman event). Kljuc po
     * {@code txId} daje svakom logickom payout eventu jedinstven kljuc, dok i dalje
     * dedupuje pravi retry istog eventa (isti txId).
     */
    private void notifyFundPayout(Long userId, String userRole, Long txId, Long fundId, String title, String message) {
        try {
            notificationService.notify(
                    userId,
                    userRole,
                    rs.raf.trading.notification.model.NotificationType.FUND_PAYOUT,
                    title,
                    message,
                    "FUND_TRANSACTION",
                    txId);
        } catch (Exception ex) {
            log.warn("Failed to send fund payout notification for user #{} (tx #{}, fund #{}): {}",
                    userId, txId, fundId, ex.getMessage());
        }
    }

    private record InvestorIdentity(Long userId, String userRole) {}

    private record InvestmentAmounts(BigDecimal amountRsd, BigDecimal debitAmount, BigDecimal fxCommission) {}

    /**
     * TODO_final C4 #14 / Sc 70: prebacuje fond izmedju distribute-to-clients
     * i reinvest-via-orders politike obrade dividendi.
     *
     * <p>Authorization: ADMIN ili SUPERVISOR (fund manager). Provera role je
     * na controller sloju ({@code @PreAuthorize}). Service dodatno proverava
     * da je supervizor stvarno manager ovog fonda; admin nema to ogranicenje.
     *
     * @param fundId id fonda
     * @param reinvest {@code true} za reinvest mod, {@code false} za distribute
     * @param actorId id pozivaoca (employee id)
     * @param isAdminActor da li pozivac ima ADMIN authority (override fund manager check)
     * @return azurirani fund detail (sa novim {@code reinvestDividends} flag-om)
     */
    @Transactional
    public InvestmentFundDetailDto updateDividendPolicy(Long fundId, Boolean reinvest, Long actorId, boolean isAdminActor) {
        if (fundId == null) {
            throw new IllegalArgumentException("Fund id must not be null");
        }
        if (reinvest == null) {
            throw new IllegalArgumentException("Reinvest flag is required");
        }

        InvestmentFund fund = investmentFundRepository.findById(fundId)
                .orElseThrow(() -> new EntityNotFoundException("Investment fund #" + fundId + " not found"));

        // Non-admin supervisor mora biti manager ovog fonda. Admin moze za bilo koji.
        if (!isAdminActor) {
            if (actorId == null || !actorId.equals(fund.getManagerEmployeeId())) {
                throw new AccessDeniedException("Samo admin ili menadzer fonda moze menjati politiku dividendi.");
            }
        }

        fund.setReinvestDividends(reinvest);
        investmentFundRepository.save(fund);

        log.info("TODO_final C4 #14 / Sc 70: dividend policy updated for fund #{} -> reinvest={} (actor={}, admin={})",
                fundId, reinvest, actorId, isAdminActor);

        return getFundDetails(fundId);
    }

    @Transactional
    public int reassignFundManager(Long oldSupervisorId, Long newAdminId) {
        if (oldSupervisorId == null || newAdminId == null) return 0;
        if (oldSupervisorId.equals(newAdminId)) return 0;
        int reassigned = investmentFundRepository.reassignManager(oldSupervisorId, newAdminId);
        if (reassigned > 0) {
            log.info("InvestmentFund manager reassigned: {} fund(s) from employee #{} to employee #{}",
                    reassigned, oldSupervisorId, newAdminId);
        }
        return reassigned;
    }

    /**
     * Ad-hoc prebacivanje vlasnistva pojedinacnog fonda na drugog supervizora.
     * Razlikuje se od bulk {@link #reassignFundManager(Long, Long)} po tome sto
     * koristi konkretan {@code fundId} umesto da prebaci sve fondove starog
     * managera. Pozivac (admin) moze ovo da uradi i kada stari manager jos uvek
     * ima isSupervisor permisiju (rucna intervencija).
     *
     * Validacije:
     * - fond mora postojati ({@link EntityNotFoundException} inace)
     * - novi manager mora postojati i biti supervizor ({@link IllegalArgumentException})
     * - novi manager mora biti razlicit od trenutnog (no-op vraca prethodno stanje)
     *
     * @return azurirani fund detail
     */
    @Transactional
    public InvestmentFundDetailDto reassignSingleFundManager(Long fundId, Long newManagerEmployeeId) {
        if (fundId == null) {
            throw new IllegalArgumentException("Fund id must not be null");
        }
        if (newManagerEmployeeId == null) {
            throw new IllegalArgumentException("New manager employee id must not be null");
        }

        InvestmentFund fund = investmentFundRepository.findById(fundId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Investment fund #" + fundId + " not found"));

        // novi manager mora biti aktivan supervizor (Celina 4 §324)
        actuaryInfoRepository.findByEmployeeId(newManagerEmployeeId)
                .filter(a -> a.getActuaryType() == ActuaryType.SUPERVISOR)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Employee #" + newManagerEmployeeId + " is not a supervisor; "
                                + "fund manager must be a supervisor."));

        Long oldManagerId = fund.getManagerEmployeeId();
        if (newManagerEmployeeId.equals(oldManagerId)) {
            log.info("reassignSingleFundManager no-op: fund #{} already managed by employee #{}",
                    fundId, newManagerEmployeeId);
            return getFundDetails(fundId);
        }

        fund.setManagerEmployeeId(newManagerEmployeeId);
        investmentFundRepository.save(fund);

        log.info("InvestmentFund #{} manager reassigned from employee #{} to employee #{} (single-fund)",
                fundId, oldManagerId, newManagerEmployeeId);

        return getFundDetails(fundId);
    }

    private Comparator<InvestmentFundSummaryDto> buildComparator(String sortField) {
        if (sortField == null) return Comparator.comparing(InvestmentFundSummaryDto::getName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        return switch (sortField.toLowerCase()) {
            case "fundvalue" -> Comparator.comparing(InvestmentFundSummaryDto::getFundValue,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "profit" -> Comparator.comparing(InvestmentFundSummaryDto::getProfit,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "minimumcontribution" -> Comparator.comparing(InvestmentFundSummaryDto::getMinimumContribution,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "inceptiondate" -> Comparator.comparing(InvestmentFundSummaryDto::getInceptionDate,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(InvestmentFundSummaryDto::getName,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        };
    }

    private <T> T safeCompute(java.util.function.Supplier<T> supplier, T fallback) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("FundValueCalculator error: {}", e.getMessage());
            return fallback;
        }
    }

    /** Citanje fond racuna preko banka-core; baca EntityNotFoundException ako ne postoji. */
    private InternalAccountDto getFundAccountOrThrow(Long accountId) {
        return getAccountOrThrow(accountId, "Fund account not found.");
    }

    /** Citanje racuna preko banka-core; 404 se mapira u EntityNotFoundException. */
    private InternalAccountDto getAccountOrThrow(Long accountId, String notFoundMessage) {
        if (accountId == null) {
            throw new EntityNotFoundException(notFoundMessage);
        }
        try {
            return bankaCoreClient.getAccount(accountId);
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() == 404) {
                throw new EntityNotFoundException(notFoundMessage);
            }
            throw ex;
        }
    }
}
