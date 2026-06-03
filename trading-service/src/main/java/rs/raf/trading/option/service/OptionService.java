package rs.raf.trading.option.service;

import io.micrometer.core.instrument.Counter;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.DebitFundsRequest;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.option.dto.OptionChainDto;
import rs.raf.trading.option.dto.OptionDto;
import rs.raf.trading.option.mapper.OptionMapper;
import rs.raf.trading.option.model.Option;
import rs.raf.trading.option.model.OptionType;
import rs.raf.trading.option.repository.OptionRepository;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.repository.ListingRepository;
import rs.raf.trading.stock.util.ListingCurrencyResolver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Servis za opcije — option chain, detalji i izvrsavanje (exercise).
 *
 * <p><b>NAPOMENA (copy-first ekstrakcija, faza 2d-C — money-seam rewiring):</b>
 * monolitna verzija je direktno menjala {@code Account.balance} /
 * {@code Account.availableBalance} bankinog USD racuna preko
 * {@code AccountRepository}. U trading-service-u racuni zive u banka-core
 * domenu, pa novcana noga exercise-a ide kroz banka-core interni
 * {@code /internal/funds/**} seam ({@link BankaCoreClient}):
 * <ul>
 *   <li>CALL exercise — {@code POST /internal/funds/debit} (banka kupuje akcije
 *       za kupca, novac napusta bankin USD racun);</li>
 *   <li>PUT exercise — {@code POST /internal/funds/credit} (banka kupuje akcije
 *       od kupca, novac stize na bankin USD racun).</li>
 * </ul>
 * Bankin USD racun se razresava preko {@link BankaCoreClient#getBankTradingAccount}
 * ("USD") umesto monolitovog {@code findBankAccountByCurrency(reg, "USD")}.
 * Portfolio mutacije (CALL buy / PUT sell) diraju samo lokalni {@link Portfolio}
 * i kopirani su verbatim. Autorizacija aktuara ({@code ensureUserCanExerciseOptions})
 * ide kroz banka-core identitet ({@link BankaCoreClient#getUserByEmail} +
 * {@link BankaCoreClient#getUserPermissions}) + lokalni {@link ActuaryInfoRepository}.
 * Idempotency kljucevi su deterministicki po (operacija, optionId, openInterest
 * pre dekrementa) — retry replay-uje umesto da dvaput naplati.
 */
@Service
public class OptionService {

    private static final Logger log = LoggerFactory.getLogger(OptionService.class);

    /**
     * Fallback valuta bankinog racuna ako se valuta listinga ne moze razresiti
     * ({@link ListingCurrencyResolver} vec sam pada na USD, pa je ovo dodatni
     * defanzivni fallback). P1-options-tax-1 (1546/1332): pre fix-a je valuta bila
     * tvrdo {@code "USD"} a {@code totalCost = strikePrice × contractSize} je u
     * valuti listinga → za ne-USD listing (BELEX/RSD strike 5000 → 5000 USD,
     * LSE/GBP, XETRA/EUR) je novcana noga bila ~120× pogresne magnitude i krsila
     * konzervaciju. Sad se valuta razresava po listingu.
     */
    private static final String FALLBACK_BANK_ACCOUNT_CURRENCY = "USD";

    private final OptionRepository optionRepository;
    private final ListingRepository listingRepository;
    private final ActuaryInfoRepository actuaryInfoRepository;
    private final PortfolioRepository portfolioRepository;
    private final BankaCoreClient bankaCoreClient;
    /** W2-T1: counter koji broji svaku uspesno izvrsenu (exercised) opciju. */
    private final Counter optionsTotal;

    public OptionService(
            OptionRepository optionRepository,
            ListingRepository listingRepository,
            ActuaryInfoRepository actuaryInfoRepository,
            PortfolioRepository portfolioRepository,
            BankaCoreClient bankaCoreClient,
            @Qualifier("optionsTotal") Counter optionsTotal) {
        this.optionRepository = optionRepository;
        this.listingRepository = listingRepository;
        this.actuaryInfoRepository = actuaryInfoRepository;
        this.portfolioRepository = portfolioRepository;
        this.bankaCoreClient = bankaCoreClient;
        this.optionsTotal = optionsTotal;
    }

    public List<OptionChainDto> getOptionsForStock(Long listingId) {
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Listing id: " + listingId + " not found."));

        List<Option> options = optionRepository.findByStockListingId(listingId);
        BigDecimal currentPrice = listing.getPrice();

        Map<LocalDate, List<Option>> grouped = options.stream()
                .collect(Collectors.groupingBy(Option::getSettlementDate));

        return grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    OptionChainDto chain = new OptionChainDto();
                    chain.setSettlementDate(entry.getKey());
                    chain.setCurrentStockPrice(currentPrice);

                    List<OptionDto> calls = entry.getValue().stream()
                            .filter(o -> o.getOptionType() == OptionType.CALL)
                            .sorted(Comparator.comparing(Option::getStrikePrice))
                            .map(o -> OptionMapper.toDto(o, currentPrice))
                            .toList();
                    chain.setCalls(calls);

                    List<OptionDto> puts = entry.getValue().stream()
                            .filter(o -> o.getOptionType() == OptionType.PUT)
                            .sorted(Comparator.comparing(Option::getStrikePrice))
                            .map(o -> OptionMapper.toDto(o, currentPrice))
                            .toList();
                    chain.setPuts(puts);

                    return chain;
                })
                .toList();
    }

    public OptionDto getOptionById(Long optionId) {
        Option option = optionRepository.findById(optionId)
                .orElseThrow(() -> new EntityNotFoundException("Option id: " + optionId + " not found."));

        BigDecimal currentPrice = option.getStockListing().getPrice();
        return OptionMapper.toDto(option, currentPrice);
    }

    @Transactional
    public void exerciseOption(Long optionId, String userEmail) {
        // R2 1426 (ACCEPTED-BY-DESIGN): opcije su instrument banke — exercise sme SAMO
        // aktuar/admin-zaposleni ({@link #ensureUserCanExerciseOptions} to enforce-uje i
        // vraca EMPLOYEE userId). Zato je {@code UserRole.EMPLOYEE} u updatePortfolioBuy/Sell
        // ispod KOREKTAN (vlasnik pozicije JE zaposleni), ne hardkod-bug.
        Long employeeUserId = ensureUserCanExerciseOptions(userEmail);

        // Pesimisticki lock — sprecava lost-update trku na openInterest izmedju
        // dva paralelna exercise-a (oba bi inace dekrementirala sa iste stale
        // vrednosti, a idempotency bi drugi settlement progutao).
        Option option = optionRepository.findByIdForUpdate(optionId)
                .orElseThrow(() -> new EntityNotFoundException("Option id: " + optionId + " not found."));

        if (option.getSettlementDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException(
                    "Opcija je istekla (settlement: " + option.getSettlementDate() + ")"
            );
        }

        BigDecimal currentPrice = option.getStockListing().getPrice();
        BigDecimal strikePrice = option.getStrikePrice();

        // [P2-input-validation-1 / R1 461] guard PRE money-leg-a — bez ovoga null
        // currentPrice obara compareTo ispod NPE-om (→ 500), a contractSize<=0 /
        // null strike daju totalCost==0 (tihi zero-value money-leg). Pretvori sve u
        // cist 400 (IllegalArgumentException → BAD_REQUEST) umesto NPE/500.
        if (currentPrice == null) {
            throw new IllegalArgumentException(
                    "Trenutna cena hartije nije dostupna — opcija se trenutno ne moze izvrsiti.");
        }
        if (strikePrice == null) {
            throw new IllegalArgumentException("Strike cena opcije nije definisana.");
        }
        if (option.getContractSize() <= 0) {
            throw new IllegalArgumentException(
                    "Velicina ugovora (contractSize) mora biti veca od 0.");
        }

        // [BE-STK-02] Per Opcije.txt + Celina 3, kupac opcije ima PRAVO (ne obavezu)
        // da je iskoristi cak i kad nije in-the-money. To je njegova ekonomska odluka
        // (gubitak je dozvoljen). Ranija logika je tvrdo blokirala OTM exercise sto
        // je suprotno spec-u. Sad samo upozoravamo i pustamo da nastavi.
        boolean isOtm = (option.getOptionType() == OptionType.CALL
                && currentPrice.compareTo(strikePrice) <= 0)
                || (option.getOptionType() == OptionType.PUT
                && currentPrice.compareTo(strikePrice) >= 0);
        if (isOtm) {
            log.warn("Option exercise OTM by {}: option id={} type={} strike={} currentPrice={} — "
                            + "allowing per spec (buyer has the right to exercise even when uneconomical).",
                    userEmail, option.getId(), option.getOptionType(), strikePrice, currentPrice);
        }

        if (option.getOpenInterest() <= 0) {
            throw new IllegalArgumentException("Opcija nema otvorenih ugovora za izvrsavanje.");
        }

        Listing stockListing = option.getStockListing();
        int contractSize = option.getContractSize();
        BigDecimal totalCost = strikePrice.multiply(BigDecimal.valueOf(contractSize))
                .setScale(4, RoundingMode.HALF_UP);

        // P1-options-tax-1 (1546/1332): valuta novcane noge MORA biti valuta
        // listinga, jer je strikePrice (i samim tim totalCost) denominovan u valuti
        // listinga. Bankin trading racun se razresava u TOJ valuti (banka-core ima
        // BANK_TRADING racun po valuti), pa nema FX konverzije ni magnitudnog
        // mismatch-a — konzervacija je egzaktna (debit/credit iznos i valuta racuna
        // se poklapaju sa denominacijom strike-a). Pre fix-a se ne-USD totalCost
        // slao na USD racun → ~120× off (RSD strike na USD racun).
        String bankCurrency = ListingCurrencyResolver.resolveSafe(
                stockListing, FALLBACK_BANK_ACCOUNT_CURRENCY);

        // openInterest PRE dekrementa — koristi se kao deterministicki idempotency
        // diskriminator (svaki exercise event ima razlicitu vrednost; retry koji je
        // rollback-ovao re-cita istu vrednost, pa banka-core replay-uje umesto dupliranja).
        int openInterestBefore = option.getOpenInterest();

        // Bankin trading racun u valuti listinga — monolit ga je razresavao kroz
        // findBankAccountByCurrency, sad ga banka-core daje preko
        // /internal/accounts/bank-trading/{currencyCode}.
        InternalAccountDto bankAccount = bankaCoreClient.getBankTradingAccount(bankCurrency);

        // [W1.11] money-leg-last: SVE lokalne JPA mutacije (portfolio + openInterest
        // dekrement) idu PRVE, a NEPOVRATNA banka-core novcana noga (debit za CALL /
        // credit za PUT) ide POSLEDNJA. Tako pad novcane noge cini da @Transactional
        // cisto rollback-uje sve lokalne izmene (nema nekonzistencije — pre fixa je
        // CALL debit mogao da prodje a portfolio update da padne → novac skinut bez
        // akcija; PUT credit je prolazio pre openInterest save → novac stvoren ako
        // taj save padne). Uspeh novcane noge je poslednji korak pre commit-a.
        if (option.getOptionType() == OptionType.CALL) {
            // CALL exercise: kupac placa strikePrice * contractSize, dobija akcije.
            // Add shares to portfolio (lokalna mutacija — pre novcane noge).
            // P1-options-tax-1 (1547/196): nabavna cena (cost-basis) iskoriscenih
            // akcija je STRIKE cena koju je kupac stvarno platio, NE trenutna trzisna
            // cena. Pre fix-a se upisivao currentPrice → cost-basis precenjen za ITM
            // CALL → kasniji SELL je prikazivao lazno manji realizovani profit (porez
            // potcenjen jer je profit od exercise-a "nestao" u napumpanom cost-basis-u).
            updatePortfolioBuy(employeeUserId, UserRole.EMPLOYEE, stockListing, contractSize, strikePrice);
        } else {
            // PUT exercise: kupac prodaje akcije po strikePrice, dobija novac.
            // Akcije se uklanjaju iz portfolija (mora ih posedovati — updatePortfolioSell
            // validira vlasnistvo i baca PRE bilo kakvog pomeranja novca).
            updatePortfolioSell(employeeUserId, UserRole.EMPLOYEE, stockListing, contractSize);
        }

        // Decrement open interest (lokalna mutacija — pre novcane noge).
        option.setOpenInterest(openInterestBefore - 1);
        optionRepository.save(option);

        // Novcana noga POSLEDNJA — nepovratna banka-core operacija. Ako padne,
        // @Transactional rollback-uje portfolio + openInterest izmene iznad.
        if (option.getOptionType() == OptionType.CALL) {
            // Novac napusta bankin racun → /internal/funds/debit. banka-core 409
            // (nedovoljno sredstava na bankinom racunu) preslikavamo u istu poruku
            // koju je monolit bacao pre rewiring-a.
            try {
                bankaCoreClient.debitFunds(
                        "option-exercise-" + optionId + "-" + openInterestBefore,
                        new DebitFundsRequest(bankAccount.id(), totalCost, BigDecimal.ZERO,
                                bankCurrency,
                                "Izvrsavanje CALL opcije " + option.getTicker()));
            } catch (BankaCoreClientException ex) {
                if (ex.getHttpStatus() == 409) {
                    throw new IllegalStateException(
                            "Nedovoljno sredstava na racunu banke za izvrsavanje CALL opcije. Potrebno: "
                                    + totalCost);
                }
                throw ex;
            }
        } else {
            // Novac stize na bankin racun → /internal/funds/credit.
            bankaCoreClient.creditFunds(
                    "option-exercise-" + optionId + "-" + openInterestBefore,
                    new CreditFundsRequest(bankAccount.id(), totalCost, BigDecimal.ZERO,
                            bankCurrency,
                            "Izvrsavanje PUT opcije " + option.getTicker()));
        }

        // W2-T1: brojaj uspesno izvrsenu opciju (i CALL i PUT).
        try {
            optionsTotal.increment();
        } catch (RuntimeException metricsEx) {
            log.warn("Failed to increment options counter for option #{}: {}",
                    option.getId(), metricsEx.getMessage());
        }

        log.info(
                "Opcija {} (id={}) izvrsena od strane {}. Tip={}, strike={}, contractSize={}, totalCost={}. Novi openInterest={}",
                option.getTicker(),
                option.getId(),
                userEmail,
                option.getOptionType(),
                strikePrice,
                contractSize,
                totalCost,
                option.getOpenInterest()
        );
    }

    /**
     * Adds shares to portfolio after CALL exercise.
     * Same pattern as OrderExecutionService.updatePortfolio for BUY.
     */
    private void updatePortfolioBuy(Long userId, String userRole, Listing listing, int quantity, BigDecimal price) {
        Optional<Portfolio> existing = portfolioRepository.findByUserIdAndUserRole(userId, userRole)
                .stream()
                .filter(p -> p.getListingId().equals(listing.getId()))
                .findFirst();

        if (existing.isPresent()) {
            Portfolio portfolio = existing.get();
            int oldQty = portfolio.getQuantity();
            BigDecimal oldTotal = portfolio.getAverageBuyPrice().multiply(BigDecimal.valueOf(oldQty));
            BigDecimal newFillTotal = price.multiply(BigDecimal.valueOf(quantity));
            int newQty = oldQty + quantity;

            BigDecimal newAvg = oldTotal.add(newFillTotal)
                    .divide(BigDecimal.valueOf(newQty), 4, RoundingMode.HALF_UP);

            portfolio.setQuantity(newQty);
            portfolio.setAverageBuyPrice(newAvg);
            portfolioRepository.save(portfolio);
        } else {
            Portfolio portfolio = new Portfolio();
            portfolio.setUserId(userId);
            portfolio.setUserRole(userRole);
            portfolio.setListingId(listing.getId());
            portfolio.setListingTicker(listing.getTicker());
            portfolio.setListingName(listing.getName());
            portfolio.setListingType(listing.getListingType().name());
            portfolio.setQuantity(quantity);
            portfolio.setAverageBuyPrice(price);
            portfolio.setPublicQuantity(0);
            portfolioRepository.save(portfolio);
        }
    }

    /**
     * Removes shares from portfolio after PUT exercise.
     * Same pattern as OrderExecutionService.updatePortfolio for SELL.
     */
    private void updatePortfolioSell(Long userId, String userRole, Listing listing, int quantity) {
        Portfolio portfolio = portfolioRepository.findByUserIdAndUserRole(userId, userRole)
                .stream()
                .filter(p -> p.getListingId().equals(listing.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Korisnik nema dovoljno akcija " + listing.getTicker() + " u portfoliju za PUT exercise."
                ));

        if (portfolio.getQuantity() < quantity) {
            throw new IllegalStateException(
                    "Nedovoljno akcija " + listing.getTicker() + " u portfoliju. Potrebno: " + quantity +
                            ", dostupno: " + portfolio.getQuantity()
            );
        }

        int newQty = portfolio.getQuantity() - quantity;
        if (newQty <= 0) {
            portfolioRepository.delete(portfolio);
        } else {
            portfolio.setQuantity(newQty);
            portfolioRepository.save(portfolio);
        }
    }

    /**
     * Proverava da li je {@code userEmail} aktivan aktuar (ili admin) i sme da
     * izvrsi opciju.
     *
     * <p>NAPOMENA (faza 2d-C): monolit je radio {@code employeeRepository.findByEmail}
     * pa proveru {@code active} + (ADMIN-permisija ILI {@code ActuaryInfo} red).
     * trading-service nema {@code employees} tabelu — identitet (id + active) se
     * razresava preko banka-core ({@link BankaCoreClient#getUserByEmail}),
     * ADMIN-permisija preko {@link BankaCoreClient#getUserPermissions}, a aktuarski
     * red se proverava nad LOKALNIM {@link ActuaryInfoRepository} (soft
     * {@code employeeId}).
     *
     * @return numericki id zaposlenog (employeeUserId) — vlasnik portfolija posle exercise-a
     */
    private Long ensureUserCanExerciseOptions(String userEmail) {
        InternalUserDto user;
        try {
            user = bankaCoreClient.getUserByEmail(userEmail);
        } catch (BankaCoreClientException ex) {
            throw new AccessDeniedException("Samo aktuar moze da izvrsi opciju.");
        }

        if (!user.active()) {
            throw new AccessDeniedException("Samo aktivan aktuar moze da izvrsi opciju.");
        }

        boolean adminEmployee = bankaCoreClient.getUserPermissions(userEmail).contains(UserRole.ADMIN);
        boolean actuaryExists = actuaryInfoRepository.findByEmployeeId(user.userId()).isPresent();

        if (!adminEmployee && !actuaryExists) {
            throw new AccessDeniedException("Samo aktuar moze da izvrsi opciju.");
        }

        return user.userId();
    }
}
