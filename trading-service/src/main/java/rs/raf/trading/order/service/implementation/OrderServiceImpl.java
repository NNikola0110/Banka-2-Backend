package rs.raf.trading.order.service.implementation;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.ReleaseFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.service.AuditLogService;
import rs.raf.trading.berza.service.ExchangeManagementService;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.notification.model.NotificationType;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.dto.OrderDto;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.exception.InsufficientHoldingsException;
import rs.raf.trading.order.mapper.OrderMapper;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.model.SagaState;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.repository.OrderSpecification;
import rs.raf.trading.order.service.BankTradingAccountResolver;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.order.service.FundReservationService;
import rs.raf.trading.order.service.ListingPriceService;
import rs.raf.trading.order.service.OrderService;
import rs.raf.trading.order.service.OrderStatusService;
import rs.raf.trading.order.service.OrderValidationService;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;
import rs.raf.trading.stock.util.ListingCurrencyResolver;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

// NAPOMENA (copy-first ekstrakcija, faza 2c — money-seam rewiring): u monolitu
// je ovaj servis citao i menjao {@code Account} direktno preko {@code AccountRepository}
// i razresavao identitet preko {@code ClientRepository}/{@code EmployeeRepository}.
// U trading-service-u racuni + identitet zive u banka-core domenu:
//   - identitet -> {@link TradingUserResolver} ({@code /internal/users/**})
//   - metadata racuna -> {@link BankaCoreClient#getAccount} / {@code getBankTradingAccount}
//   - rezervacija/oslobadjanje sredstava -> {@link FundReservationService} ({@code /internal/funds/**})
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final ListingRepository listingRepository;
    private final ActuaryInfoRepository actuaryInfoRepository;
    private final OrderValidationService orderValidationService;
    private final ListingPriceService listingPriceService;
    private final OrderStatusService orderStatusService;
    private final ExchangeManagementService exchangeManagementService;
    private final FundReservationService fundReservationService;
    private final BankTradingAccountResolver bankTradingAccountResolver;
    private final CurrencyConversionService currencyConversionService;
    private final PortfolioRepository portfolioRepository;
    private final InvestmentFundRepository investmentFundRepository;
    private final BankaCoreClient bankaCoreClient;
    private final TradingUserResolver tradingUserResolver;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    @Override
    @Transactional
    public OrderDto createOrder(CreateOrderDto dto, boolean internalActor) {
        // NOTE: OTP guard za public REST flow se izvrsava u OrderController-u
        // (banka-core /internal/otp/verify) PRE poziva ove metode. Za interne
        // pozivace (RecurringOrderService scheduler), internalActor=true
        // dokumentuje da OTP guard ne stoji uzvodno i NE smemo da pretpostavimo
        // realan korisnik koji je uneo TOTP kod (sistemska akcija).
        if (internalActor) {
            org.slf4j.LoggerFactory.getLogger(OrderServiceImpl.class)
                    .info("createOrder: internal actor flow (OTP guard preskocen)");
        }

        // Step 1: Validate input
        orderValidationService.validate(dto);

        OrderType orderType = orderValidationService.parseOrderType(dto.getOrderType());
        OrderDirection direction = orderValidationService.parseDirection(dto.getDirection());

        // Step 2: Fetch listing
        Listing listing = listingRepository.findById(dto.getListingId())
                .orElseThrow(() -> new EntityNotFoundException("Listing not found"));

        // Step 3: Determine price
        BigDecimal pricePerUnit = listingPriceService.getPricePerUnit(dto, listing, orderType, direction);
        BigDecimal approximatePrice = listingPriceService.calculateApproximatePrice(
                dto.getContractSize(), pricePerUnit, dto.getQuantity());

        // Step 4: Resolve current user
        UserContext userContext = resolveCurrentUser();
        boolean isEmployee = UserRole.isEmployee(userContext.userRole());

        // Step 5: Resolve account.
        //   Klijent: licni racun
        //   Supervizor sa fundId: fond.account (RSD) — P3 / Celina 4 (Nova) §3883-3964
        //   Supervizor/agent bez fundId: bankin trading racun (postojeci flow)
        String listingCurrencyCode = resolveListingCurrency(listing);
        final InvestmentFund fund;
        InternalAccountDto account;
        if (dto.getFundId() != null) {
            if (!isEmployee) {
                throw new AccessDeniedException(
                        "Samo supervizori mogu da kupuju u ime investicionog fonda.");
            }
            fund = investmentFundRepository.findById(dto.getFundId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Investicioni fond ne postoji: " + dto.getFundId()));
            // P5 — proverava se da je supervizor manager fonda; ako nije, 403.
            if (!userContext.userId().equals(fund.getManagerEmployeeId())) {
                throw new AccessDeniedException(
                        "Niste manager fonda " + fund.getName() + " — ne mozete kupovati u njegovo ime.");
            }
            account = getAccountOrThrow(fund.getAccountId(),
                    "Racun fonda ne postoji: " + fund.getAccountId());
        } else {
            fund = null;
            // accountId je opcioni (vec validovan kao null-able na DTO-u zbog
            // XOR sa fundId-jem). resolveTradingAccount handluje:
            //   - accountId != null → konkretan racun
            //   - accountId == null + zaposleni → automatski bankin trading racun u listing valuti
            //   - accountId == null + klijent → 404 "Racun ne postoji: null"
            account = resolveTradingAccount(dto.getAccountId(), isEmployee, listingCurrencyCode);
        }
        Portfolio portfolio = null;
        if (direction == OrderDirection.SELL) {
            // Fund SELL: hartija je u portfoliju sa user_role=FUND i user_id=fund.id,
            // ne pod trenutnim supervizorom. Bez ovog branch-a BE bi rekao
            // "Nemate ovu hartiju u portfoliju" iako fond stvarno ima hartiju.
            Long lookupUserId = fund != null ? fund.getId() : userContext.userId();
            String lookupUserRole = fund != null ? UserRole.FUND : userContext.userRole();
            portfolio = portfolioRepository
                    .findByUserIdAndUserRoleAndListingIdForUpdate(lookupUserId, lookupUserRole, listing.getId())
                    .orElseThrow(() -> new InsufficientHoldingsException(
                            "Nemate ovu hartiju u portfoliju"));
            int available = portfolio.getAvailableQuantity();
            if (available < dto.getQuantity()) {
                throw new InsufficientHoldingsException(
                        "Nedovoljno raspolozivih hartija. Dostupno: " + available
                                + ", traženo: " + dto.getQuantity());
            }
        }

        BigDecimal exchangeRate = null;
        BigDecimal totalReservation = null;
        BigDecimal fxCommission = BigDecimal.ZERO;
        // account je uvek non-null nakon if/else iznad — guard zadrzan iz citljivosti uklonjen
        if (direction == OrderDirection.BUY) {
            String accountCurrencyCode = account.currencyCode();
            boolean chargeFx = !isEmployee && !listingCurrencyCode.equals(accountCurrencyCode);

            CurrencyConversionService.ConversionResult priceConv = currencyConversionService
                    .convertForPurchase(approximatePrice, listingCurrencyCode, accountCurrencyCode, chargeFx);
            exchangeRate = priceConv.midRate();
            BigDecimal approxInAccountCurrency = priceConv.amount();
            fxCommission = priceConv.commission();

            // Provizija se obracunava u listing (USD-denominovanoj) valuti, zatim se konvertuje
            // u valutu racuna — tako cap od $7/$12 ostaje ispravan za sve kombinacije valuta.
            // Na FX konverziju provizije ordera takodje se primenjuje menjacnica (ako je obracunata).
            BigDecimal commissionInAccountCurrency;
            if (isEmployee) {
                commissionInAccountCurrency = BigDecimal.ZERO;
            } else {
                BigDecimal commissionInListingCurrency = calculateCommissionInListingCurrency(approximatePrice, orderType);
                CurrencyConversionService.ConversionResult commConv = currencyConversionService
                        .convertForPurchase(commissionInListingCurrency, listingCurrencyCode, accountCurrencyCode, chargeFx);
                commissionInAccountCurrency = commConv.amount();
                fxCommission = fxCommission.add(commConv.commission());
            }
            totalReservation = approxInAccountCurrency.add(commissionInAccountCurrency)
                    .setScale(4, RoundingMode.HALF_UP);
        } else { // SELL
            // Za SELL ne rezervisemo novac; ipak sacuvamo kurs listing→receiving account
            // kako bi fill engine znao u kojoj valuti da prihoduje pare na receiving racun.
            String accountCurrencyCode = account.currencyCode();
            exchangeRate = currencyConversionService.getRate(listingCurrencyCode, accountCurrencyCode);
        }

        // Step 6: Verify funds / holdings
        //   BUY: availableBalance >= totalReservation
        //   SELL: portfolio.availableQuantity >= dto.quantity (provereno iznad pri portfolio lookup-u)
        if (direction == OrderDirection.BUY) {
            if (account.availableBalance().compareTo(totalReservation) < 0) {
                throw new InsufficientFundsException(
                        "Nedovoljno raspolozivih sredstava na racunu " + account.accountNumber());
            }
        }

        // Step 7: Determine status
        OrderStatus status = orderStatusService.determineStatus(userContext.userRole(), userContext.userId(), approximatePrice);
        String approvedBy = (status == OrderStatus.APPROVED) ? "No need for approval" : null;

        // Step 8: Compute afterHours
        boolean afterHours = computeAfterHours(listing);

        // Step 9: Build and save order
        Order order = OrderMapper.fromCreateDto(dto, listing);
        order.setUserId(userContext.userId());
        // S44 fix: eksplicitno setujemo userRole sa resolved userContext-a
        order.setUserRole(userContext.userRole());
        order.setPricePerUnit(pricePerUnit);
        order.setApproximatePrice(approximatePrice);
        order.setStatus(status);
        order.setApprovedBy(approvedBy);
        order.setAfterHours(afterHours);
        if (fund != null) {
            order.setFundId(fund.getId());
            // Za fond-ordere: userId je fundId i userRole je "FUND" (ne supervizora)
            order.setUserId(fund.getId());
            order.setUserRole(UserRole.FUND);
        }

        if (direction == OrderDirection.BUY) {
            order.setReservedAccountId(account.id());
            order.setReservedAmount(totalReservation);
            order.setExchangeRate(exchangeRate);
            order.setFxCommission(fxCommission.compareTo(BigDecimal.ZERO) > 0 ? fxCommission : null);
            // Za agente pisemo bankin racun i na accountId da fill ima referencu
            if (isEmployee) {
                order.setAccountId(account.id());
            }
        } else { // SELL
            // Za SELL "reservedAccountId" drzi receiving account (kuda idu pare po fill-u).
            // reservedAmount ostaje null — nema novcane rezervacije.
            order.setReservedAccountId(account.id());
            order.setExchangeRate(exchangeRate);
            if (isEmployee) {
                order.setAccountId(account.id());
            }
        }

        if (status == OrderStatus.APPROVED) {
            order.setApprovedAt(LocalDateTime.now());
        }

        Order savedOrder = orderRepository.save(order);

        // Step 10: Rezervacija (sredstva za BUY, kolicina hartija za SELL) za APPROVED ordere
        if (status == OrderStatus.APPROVED) {
            if (direction == OrderDirection.BUY) {
                fundReservationService.reserveForBuy(savedOrder);
            } else { // SELL — portfolio je uvek non-null nakon SELL grane iznad
                fundReservationService.reserveForSell(savedOrder, portfolio);
            }
        }

        // Step 11: Update agent usedLimit if APPROVED
        if (status == OrderStatus.APPROVED && isEmployee) {
            final BigDecimal limitDelta = totalReservation != null ? totalReservation : approximatePrice;
            Optional<ActuaryInfo> actuaryOpt = orderStatusService.getAgentInfo(userContext.userId());
            actuaryOpt.ifPresent(actuary -> {
                if (actuary.getActuaryType() == ActuaryType.AGENT) {
                    BigDecimal current = actuary.getUsedLimit() != null ? actuary.getUsedLimit() : BigDecimal.ZERO;
                    actuary.setUsedLimit(current.add(limitDelta));
                    actuaryInfoRepository.save(actuary);
                }
            });
        }

        // Step 12: Execution handled by OrderScheduler cron job

        if (savedOrder.getStatus() == OrderStatus.PENDING) {
            try {
                notificationService.notify(
                        savedOrder.getUserId(),
                        savedOrder.getUserRole(),
                        NotificationType.ORDER_PENDING,
                        "Nalog čeka odobrenje",
                        "Vaš nalog za " + savedOrder.getListing().getTicker() + " je kreiran i čeka odobrenje supervizora.",
                        "ORDER",
                        savedOrder.getId()
                );
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(OrderServiceImpl.class)
                        .warn("Failed to send order pending notification: {}", e.getMessage());
            }
        }

        return toDtoWithUserName(savedOrder);
    }

    /**
     * Resolve-uje ISO kod valute za dati listing. Delegira na
     * {@link ListingCurrencyResolver} — jedinstven util koriscen u vise
     * servisa (tax, OTC).
     */
    private String resolveListingCurrency(Listing listing) {
        return ListingCurrencyResolver.resolve(listing);
    }

    /**
     * Zajednicka logika za pronalazenje trading racuna za BUY i SELL orderi:
     *  - ako je {@code accountId} eksplicitno prosledjen, load-uje se preko banka-core;
     *  - inace ako je korisnik zaposleni, uzima se bankin racun u valuti hartije;
     *  - inace je to greska (klijent mora navesti racun).
     *
     * NAPOMENA (2c): monolitni {@code findForUpdateById} pessimistic lock vise ne
     * postoji u trading-service-u — racun zivi u banka-core. Provera balansa pre
     * rezervacije je samo metadata; stvarna garancija je banka-core {@code reserve}
     * koji vraca 409.
     */
    private InternalAccountDto resolveTradingAccount(Long accountId, boolean isEmployee, String listingCurrencyCode) {
        if (accountId != null) {
            return getAccountOrThrow(accountId, "Racun ne postoji: " + accountId);
        }
        if (isEmployee) {
            return bankTradingAccountResolver.resolve(listingCurrencyCode);
        }
        throw new EntityNotFoundException("Racun ne postoji: null");
    }

    /**
     * Cita racun preko banka-core internog seam-a; banka-core 404 se prevodi u
     * {@link EntityNotFoundException} (verno monolitovom {@code orElseThrow}).
     */
    private InternalAccountDto getAccountOrThrow(Long accountId, String notFoundMessage) {
        try {
            return bankaCoreClient.getAccount(accountId);
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() == 404) {
                throw new EntityNotFoundException(notFoundMessage);
            }
            throw ex;
        }
    }

    /**
     * Racuna proviziju u valuti listinga (gde USD cap ima smisla).
     * Spec: Market min(14% * cena, $7), Limit min(24% * cena, $12).
     * Za non-USD listinge, cap od $7/$12 se tretira kao literal iznos
     * u listing valuti — pragmaticna aproksimacija jer se vecina listinga
     * denominuje u USD.
     */
    private BigDecimal calculateCommissionInListingCurrency(BigDecimal approxInListingCurrency, OrderType orderType) {
        return switch (orderType) {
            case MARKET, STOP -> approxInListingCurrency.multiply(new BigDecimal("0.14"))
                    .min(new BigDecimal("7"))
                    .setScale(4, RoundingMode.HALF_UP);
            case LIMIT, STOP_LIMIT -> approxInListingCurrency.multiply(new BigDecimal("0.24"))
                    .min(new BigDecimal("12"))
                    .setScale(4, RoundingMode.HALF_UP);
        };
    }

    @Override
    @Transactional
    public OrderDto approveOrder(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found" + orderId));

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalStateException("Only PENDING orders can be approved");
        }

        String supervisorName = getSupervisorName();

        Listing listing = order.getListing();
        if (listing.getSettlementDate() != null &&
                listing.getSettlementDate().isBefore(java.time.LocalDate.now())) {
            order.setStatus(OrderStatus.DECLINED);
            order.setApprovedBy(supervisorName);
            order.setLastModification(LocalDateTime.now());
            Order saved = orderRepository.save(order);
            return toDtoWithUserName(saved);
        }

        // Phase 5.1: Rezervacija sredstava / hartija u trenutku odobravanja.
        // Cena se mogla promeniti izmedju PENDING i sada — koristimo
        // order.approximatePrice kao polaznu tacku (vec izracunato pri createOrder).
        boolean isEmployee = UserRole.isEmployee(order.getUserRole());
        String listingCurrencyCode = resolveListingCurrency(listing);
        BigDecimal totalReservation = null;

        if (order.getDirection() == OrderDirection.BUY) {
            InternalAccountDto account;
            Long accountId = order.getReservedAccountId() != null
                    ? order.getReservedAccountId()
                    : order.getAccountId();
            if (accountId != null) {
                account = getAccountOrThrow(accountId, "Racun ne postoji: " + accountId);
            } else if (isEmployee) {
                account = bankTradingAccountResolver.resolve(listingCurrencyCode);
            } else {
                throw new EntityNotFoundException("Order nema povezan racun za rezervaciju");
            }

            String accountCurrencyCode = account.currencyCode();
            boolean chargeFx = !isEmployee && !listingCurrencyCode.equals(accountCurrencyCode);
            BigDecimal approxInListing = order.getApproximatePrice() != null
                    ? order.getApproximatePrice()
                    : BigDecimal.ZERO;

            CurrencyConversionService.ConversionResult priceConv = currencyConversionService
                    .convertForPurchase(approxInListing, listingCurrencyCode, accountCurrencyCode, chargeFx);
            BigDecimal exchangeRate = priceConv.midRate();
            BigDecimal approxInAccountCurrency = priceConv.amount();
            BigDecimal fxCommission = priceConv.commission();

            BigDecimal commissionInAccountCurrency;
            if (isEmployee) {
                commissionInAccountCurrency = BigDecimal.ZERO;
            } else {
                BigDecimal commissionInListing = calculateCommissionInListingCurrency(
                        approxInListing, order.getOrderType());
                CurrencyConversionService.ConversionResult commConv = currencyConversionService
                        .convertForPurchase(commissionInListing, listingCurrencyCode, accountCurrencyCode, chargeFx);
                commissionInAccountCurrency = commConv.amount();
                fxCommission = fxCommission.add(commConv.commission());
            }
            totalReservation = approxInAccountCurrency.add(commissionInAccountCurrency)
                    .setScale(4, RoundingMode.HALF_UP);

            if (account.availableBalance().compareTo(totalReservation) < 0) {
                throw new InsufficientFundsException(
                        "Nedovoljno sredstava u trenutku odobravanja na racunu " + account.accountNumber());
            }

            order.setReservedAccountId(account.id());
            order.setReservedAmount(totalReservation);
            order.setExchangeRate(exchangeRate);
            order.setFxCommission(fxCommission.compareTo(BigDecimal.ZERO) > 0 ? fxCommission : null);
            if (isEmployee) {
                order.setAccountId(account.id());
            }
            fundReservationService.reserveForBuy(order);
        } else { // SELL

            Portfolio portfolio = portfolioRepository
                    .findByUserIdAndUserRoleAndListingIdForUpdate(order.getUserId(), order.getUserRole(), listing.getId())
                    .orElseThrow(() -> new InsufficientHoldingsException(
                            "Nemate ovu hartiju u portfoliju"));
            if (portfolio.getAvailableQuantity() < order.getQuantity()) {
                throw new InsufficientHoldingsException(
                        "Nedovoljno raspolozivih hartija. Dostupno: "
                                + portfolio.getAvailableQuantity() + ", traženo: " + order.getQuantity());
            }
            fundReservationService.reserveForSell(order, portfolio);
        }

        order.setStatus(OrderStatus.APPROVED);
        order.setApprovedBy(supervisorName);
        order.setApprovedAt(LocalDateTime.now());
        order.setLastModification(LocalDateTime.now());

        Order saved = orderRepository.save(order);

        // Update agent usedLimit when supervisor approves — koristimo totalReservation
        // (u valuti racuna) kao delta. Za SELL nema novcane rezervacije pa padamo na
        // approximatePrice fallback radi backward compat.
        if (isEmployee) {
            final BigDecimal limitDelta = totalReservation != null
                    ? totalReservation
                    : (order.getApproximatePrice() != null ? order.getApproximatePrice() : BigDecimal.ZERO);
            Optional<ActuaryInfo> actuaryOpt = orderStatusService.getAgentInfo(order.getUserId());
            actuaryOpt.ifPresent(actuary -> {
                if (actuary.getActuaryType() == ActuaryType.AGENT) {
                    BigDecimal current = actuary.getUsedLimit() != null ? actuary.getUsedLimit() : BigDecimal.ZERO;
                    actuary.setUsedLimit(current.add(limitDelta));
                    actuaryInfoRepository.save(actuary);
                }
            });
        }

        try {
            notificationService.notify(
                    saved.getUserId(),
                    saved.getUserRole(),
                    NotificationType.ORDER_APPROVED,
                    "Nalog odobren",
                    "Vaš nalog za " + saved.getListing().getTicker() + " je odobren i biće izvršen.",
                    "ORDER",
                    saved.getId()
            );
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(OrderServiceImpl.class)
                    .warn("Failed to send order approved notification: {}", e.getMessage());
        }

        // B7 audit hook (port iz main PR #86, Stasa Dragovic)
        auditLogService.record(
                resolveCurrentUser().userId(), "EMPLOYEE",
                AuditActionType.ORDER_APPROVED,
                "Order approved: " + saved.getId(),
                "ORDER", saved.getId());

        return toDtoWithUserName(saved);
    }

    @Override
    @Transactional
    public OrderDto declineOrder(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found " + orderId));

        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.APPROVED) {
            throw new IllegalStateException("Only PENDING or APPROVED orders can be declined/cancelled");
        }

        String supervisorName = getSupervisorName();

        // Phase 5.2: Ako je order bio APPROVED, treba osloboditi rezervaciju
        // (novcanu za BUY, kolicinu hartija za SELL) + rollback agent usedLimit.
        boolean hadReservation = order.getStatus() == OrderStatus.APPROVED;
        if (hadReservation && !order.isReservationReleased()) {
            if (order.getDirection() == OrderDirection.BUY) {
                fundReservationService.releaseForBuy(order);
            } else { // SELL
                Portfolio portfolio = portfolioRepository
                        .findByUserIdAndUserRoleAndListingIdForUpdate(order.getUserId(), order.getUserRole(), order.getListing().getId())
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Portfolio ne postoji za order " + order.getId()));
                fundReservationService.releaseForSell(order, portfolio);
            }

            if (UserRole.isEmployee(order.getUserRole()) && order.getReservedAmount() != null) {
                Optional<ActuaryInfo> actuaryOpt = orderStatusService.getAgentInfo(order.getUserId());
                actuaryOpt.ifPresent(actuary -> {
                    if (actuary.getActuaryType() == ActuaryType.AGENT) {
                        BigDecimal current = actuary.getUsedLimit() != null ? actuary.getUsedLimit() : BigDecimal.ZERO;
                        BigDecimal rolledBack = current.subtract(order.getReservedAmount());
                        actuary.setUsedLimit(rolledBack.max(BigDecimal.ZERO));
                        actuaryInfoRepository.save(actuary);
                    }
                });
            }
        }

        order.setStatus(OrderStatus.DECLINED);
        order.setApprovedBy(supervisorName);
        order.setLastModification(LocalDateTime.now());

        Order saved = orderRepository.save(order);

        try {
            notificationService.notify(
                    saved.getUserId(),
                    saved.getUserRole(),
                    NotificationType.ORDER_DECLINED,
                    "Nalog odbijen",
                    "Vaš nalog za " + (saved.getListing() != null ? saved.getListing().getTicker() : "") + " je odbijen.",
                    "ORDER",
                    saved.getId()
            );
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(OrderServiceImpl.class)
                    .warn("Failed to send order declined notification: {}", e.getMessage());
        }

        // B7 audit hook (port iz main PR #86, Stasa Dragovic)
        auditLogService.record(
                resolveCurrentUser().userId(), "EMPLOYEE",
                AuditActionType.ORDER_DECLINED,
                "Order declined: " + saved.getId(),
                "ORDER", saved.getId());

        return toDtoWithUserName(saved);
    }

    @Override
    @Transactional
    public OrderDto cancelOrder(Long orderId, Integer quantityToCancel) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found " + orderId));

        int remaining = order.getRemainingPortions() != null
                ? order.getRemainingPortions()
                : (order.getQuantity() != null ? order.getQuantity() : 0);

        // Full cancel delegates to declineOrder which handles both PENDING
        // and APPROVED states (releases reservation + rollbackuje usedLimit).
        boolean fullCancel = quantityToCancel == null
                || quantityToCancel <= 0
                || quantityToCancel >= remaining
                || order.getStatus() == OrderStatus.PENDING
                || order.isDone();
        if (fullCancel) {
            return declineOrder(orderId);
        }

        if (order.getStatus() != OrderStatus.APPROVED) {
            throw new IllegalStateException(
                    "Parcijalni cancel je dozvoljen samo za APPROVED ordere.");
        }

        int cancelQty = quantityToCancel;
        int newRemaining = remaining - cancelQty;
        Integer originalQty = order.getQuantity();

        // Oslobodi pro-ratu rezervisanog sredstva / hartija.
        //
        // NAPOMENA (2c rewiring): monolit je u parcijalnom cancel-u direktno
        // umanjivao Account.reservedAmount za pro-rata iznos. banka-core
        // /internal/funds/.../release oslobadja CELU preostalu rezervaciju
        // (nema parcijalnog release-a). Verni ekvivalent koji postuje seam:
        //   1. oslobodi celu trenutnu rezervaciju (releaseForBuy),
        //   2. odmah re-rezervisi pro-rata jos-zeljeni iznos
        //      (reservedAmount * newRemaining / originalQty).
        // Neto efekat = oslobadjanje tacno cancelQty/originalQty dela, isto kao
        // monolitno umanjenje — klijentu se raspoloziva sredstva odmah povecaju.
        // Per-fill commit model i dalje nesmetano radi nad novom rezervacijom.
        // Originalna rezervacija PRE re-rezervacije — agent usedLimit rollback
        // (nize) mora citati ovu vrednost, ne prepisani (umanjeni) order.reservedAmount.
        // Verno monolitu, koji u cancelOrder uopste ne prepisuje order.reservedAmount
        // pa njegov rollback uvek koristi originalnu rezervaciju.
        BigDecimal originalReservedAmount = order.getReservedAmount();
        if (order.getDirection() == OrderDirection.BUY
                && order.getReservedAccountId() != null
                && order.getReservedAmount() != null
                && originalQty != null && originalQty > 0) {
            BigDecimal fraction = new BigDecimal(newRemaining)
                    .divide(new BigDecimal(originalQty), 10, RoundingMode.HALF_UP);
            BigDecimal newReservation = order.getReservedAmount().multiply(fraction)
                    .setScale(4, RoundingMode.HALF_UP);

            // 1. oslobodi celu preostalu rezervaciju
            if (!order.isReservationReleased() && order.getBankaCoreReservationId() != null) {
                bankaCoreClient.releaseFunds(
                        order.getBankaCoreReservationId(),
                        "order-" + order.getId() + "-cancel-release",
                        new ReleaseFundsRequest(
                                "Parcijalni cancel ordera " + order.getId()
                                        + " — oslobadjanje pre re-rezervacije"));
            }

            // 2. re-rezervisi pro-rata jos-zeljeni iznos
            if (newReservation.signum() > 0) {
                String currencyCode = bankaCoreClient.getAccount(order.getReservedAccountId())
                        .currencyCode();
                try {
                    ReserveFundsResponse response = bankaCoreClient.reserveFunds(
                            "order-" + order.getId() + "-cancel-rereserve",
                            new ReserveFundsRequest(order.getReservedAccountId(),
                                    newReservation, currencyCode));
                    order.setBankaCoreReservationId(response.reservationId());
                    order.setReservedAmount(newReservation);
                    order.setSagaState(SagaState.FUNDS_RESERVED);
                    order.setReservationReleased(false);
                } catch (BankaCoreClientException ex) {
                    if (ex.getHttpStatus() == 409) {
                        throw new InsufficientFundsException(
                                "Nedovoljno sredstava za re-rezervaciju posle parcijalnog cancel-a");
                    }
                    throw ex;
                }
            } else {
                order.setReservedAmount(BigDecimal.ZERO);
                order.setReservationReleased(true);
                order.setSagaState(SagaState.COMPENSATED);
            }
        } else if (order.getDirection() == OrderDirection.SELL) {
            Portfolio portfolio = portfolioRepository
                    .findByUserIdAndUserRoleAndListingIdForUpdate(order.getUserId(), order.getUserRole(), order.getListing().getId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Portfolio ne postoji za order " + order.getId()));
            int releaseQty = Math.min(cancelQty, portfolio.getReservedQuantity());
            portfolio.setReservedQuantity(portfolio.getReservedQuantity() - releaseQty);
            portfolioRepository.save(portfolio);
        }

        // Rollback proporcionalnog usedLimit-a za AGENT-a.
        // VAZNO: rollback se racuna iz originalReservedAmount (rezervacija PRE
        // parcijalnog cancel-a), ne iz order.getReservedAmount() koji je gore vec
        // prepisan na umanjenu re-rezervaciju — inace bi usedLimit bio premalo
        // vracen agentu (npr. order qty 10, original 1000, cancel 4 → treba
        // 1000*4/10 = 400, a sa prepisanom vrednoscu bi bilo samo 600*4/10 = 240).
        if (order.getDirection() == OrderDirection.BUY
                && UserRole.isEmployee(order.getUserRole())
                && originalReservedAmount != null
                && originalQty != null && originalQty > 0) {
            Optional<ActuaryInfo> actuaryOpt = orderStatusService.getAgentInfo(order.getUserId());
            int cancelQtyFinal = cancelQty;
            actuaryOpt.ifPresent(actuary -> {
                if (actuary.getActuaryType() == ActuaryType.AGENT) {
                    BigDecimal fraction = new BigDecimal(cancelQtyFinal)
                            .divide(new BigDecimal(originalQty), 10, RoundingMode.HALF_UP);
                    BigDecimal rollback = originalReservedAmount.multiply(fraction)
                            .setScale(4, RoundingMode.HALF_UP);
                    BigDecimal current = actuary.getUsedLimit() != null
                            ? actuary.getUsedLimit() : BigDecimal.ZERO;
                    actuary.setUsedLimit(current.subtract(rollback).max(BigDecimal.ZERO));
                    actuaryInfoRepository.save(actuary);
                }
            });
        }

        order.setRemainingPortions(newRemaining);
        order.setLastModification(LocalDateTime.now());
        order.setApprovedBy(getSupervisorName()); // audit trail ko je skratio order
        Order saved = orderRepository.save(order);
        return toDtoWithUserName(saved);
    }

    @Override
    public Page<OrderDto> getAllOrders(String status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        if (status == null || status.isBlank() || status.equalsIgnoreCase("ALL")) {
            return orderRepository.findAll(pageable).map(this::toDtoWithUserName);
        }

        OrderStatus orderStatus;
        try {
            orderStatus = OrderStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status: " + status +
                    ". Valid status: ALL, PENDING, APPROVED, DECLINED, DONE");
        }

        return orderRepository.findByStatus(orderStatus, pageable).map(this::toDtoWithUserName);
    }

    @Override
    public Page<OrderDto> getMyOrders(int page, int size, String status, LocalDate dateFrom, LocalDate dateTo, String listingType) {
        UserContext userContext = resolveCurrentUser();
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        OrderStatus parsedStatus = null;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            try {
                parsedStatus = OrderStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }

        ListingType parsedListingType = null;
        if (listingType != null && !listingType.isBlank()) {
            try {
                parsedListingType = ListingType.valueOf(listingType.toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }

        Specification<Order> spec = Specification
                .where(OrderSpecification.hasUserId(userContext.userId()))
                .and(OrderSpecification.hasStatus(parsedStatus))
                .and(OrderSpecification.createdAfter(dateFrom))
                .and(OrderSpecification.createdBefore(dateTo))
                .and(OrderSpecification.hasListingType(parsedListingType));

        return orderRepository.findAll(spec, pageable).map(this::toDtoWithUserName);
    }

    @Override
    public OrderDto getOrderById(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order with ID " + orderId + " not found"));

        boolean isSupervisor = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_EMPLOYEE"));

        if (isSupervisor) {
            return toDtoWithUserName(order);
        }

        Long currentUserId = resolveCurrentUser().userId();
        if (!order.getUserId().equals(currentUserId)) {
            throw new IllegalStateException("You dont have access to this account");
        }

        return toDtoWithUserName(order);
    }

    /**
     * Razresava identitet trenutnog korisnika preko {@link TradingUserResolver}
     * ({@code /internal/users/by-email/**}). Zamena za monolitnu
     * {@code ClientRepository}/{@code EmployeeRepository} pretragu.
     */
    private UserContext resolveCurrentUser() {
        return tradingUserResolver.resolveCurrent();
    }

    private boolean computeAfterHours(Listing listing) {
        String exchange = listing.getExchangeAcronym();
        if (exchange == null) return false;

        try {
            return exchangeManagementService.isAfterHours(exchange);
        } catch (Exception e) {
            // Exchange not found or unknown — treat as not after-hours
            return false;
        }
    }

    private String getSupervisorName() {
        UserContext userContext = resolveCurrentUser();
        return tradingUserResolver.resolveName(userContext.userId(), userContext.userRole());
    }

    private OrderDto toDtoWithUserName(Order order) {
        String userName = tradingUserResolver.resolveName(order.getUserId(), order.getUserRole());
        return OrderMapper.toDto(order, userName);
    }
}
