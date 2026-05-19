package rs.raf.trading.investmentfund.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.banka2.contracts.internal.TransferFundsResponse;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.investmentfund.dto.InvestmentFundDtos.ClientFundPositionDto;
import rs.raf.trading.investmentfund.dto.InvestmentFundDtos.ClientFundTransactionDto;
import rs.raf.trading.investmentfund.dto.InvestmentFundDtos.InvestFundDto;
import rs.raf.trading.investmentfund.dto.InvestmentFundDtos.WithdrawFundDto;
import rs.raf.trading.investmentfund.model.*;
import rs.raf.trading.investmentfund.repository.*;
import rs.raf.trading.investmentfund.scheduler.FundValueSnapshotScheduler;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T8 — Celina 4 (Nova), Investicioni fondovi: invest / withdraw.
 *
 * NAPOMENA (faza 2c, money-seam adaptacija): monolitna verzija je direktno
 * mutirala {@code Account.balance}/{@code availableBalance} (asercije nad
 * stanjem racuna) i citala racune preko {@code AccountRepository.findForUpdateById}.
 * trading-service verzija radi novcanu nogu kroz banka-core interni settlement
 * seam: {@code invest} radi {@code POST /internal/funds/transfer} sa idempotency
 * kljucem {@code fund-invest-<txId>}, {@code withdraw} (executePayout) sa
 * {@code fund-payout-<txId>}. Racuni se citaju kao {@link InternalAccountDto}
 * preko {@link BankaCoreClient#getAccount}.
 *
 * <p>Zbog toga su asercije nad {@code Account} stanjem zamenjene verifikacijom
 * da je {@link BankaCoreClient#transferFunds} pozvan sa ispravnim
 * {@link TransferFundsRequest} ({@code debitAmount} / {@code creditAmount} /
 * {@code commission} / {@code commissionCurrency}) i deterministickim
 * idempotency kljucem. Stvarna mutacija stanja racuna je banka-core posao
 * (pokriva je banka-core {@code internalapi} test suite). FX matematika,
 * provera minimuma, pozicija upsert/decrease, status tranzicije
 * ({@code PENDING} -&gt; {@code COMPLETED}) i {@code FundLiquidationService}
 * okidanje su lokalna trading-service logika i ostaju doslovno pokrivene.
 * "Nedovoljno sredstava" putanja stubuje {@code transferFunds} da baci
 * {@link BankaCoreClientException} sa 409.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvestmentFundServiceT8UnitTest {

    private static final String BANK_EMAIL = "banka2.doo@banka.rs";

    @Mock private FundValueSnapshotRepository fundValueSnapshotRepository;
    @Mock private InvestmentFundRepository investmentFundRepository;
    @Mock private ClientFundPositionRepository clientFundPositionRepository;
    @Mock private ClientFundTransactionRepository clientFundTransactionRepository;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private rs.raf.trading.security.TradingUserResolver tradingUserResolver;
    @Mock private ActuaryInfoRepository actuaryInfoRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private FundValueCalculator fundValueCalculator;
    @Mock private FundLiquidationService fundLiquidationService;
    @Mock private CurrencyConversionService currencyConversionService;
    @Mock private FundValueSnapshotScheduler fundValueSnapshotScheduler;

    @InjectMocks
    private InvestmentFundService service;

    private final AtomicLong txId = new AtomicLong(1);
    private final AtomicLong positionId = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "bankOwnerClientEmail", BANK_EMAIL);

        when(clientFundTransactionRepository.save(any(ClientFundTransaction.class))).thenAnswer(invocation -> {
            ClientFundTransaction tx = invocation.getArgument(0);
            if (tx.getId() == null) {
                tx.setId(txId.getAndIncrement());
            }
            return tx;
        });

        when(clientFundPositionRepository.save(any(ClientFundPosition.class))).thenAnswer(invocation -> {
            ClientFundPosition position = invocation.getArgument(0);
            if (position.getId() == null) {
                position.setId(positionId.getAndIncrement());
            }
            return position;
        });

        when(fundValueCalculator.computeFundValue(any())).thenReturn(BigDecimal.ZERO);
        when(fundValueCalculator.computeProfit(any())).thenReturn(BigDecimal.ZERO);
        when(clientFundPositionRepository.findByFundId(anyLong())).thenReturn(java.util.List.of());
        // banka-core transferFunds — happy path stub; pojedinacni testovi koji
        // pokrivaju 409 (insufficient funds) prekrivaju ovo.
        when(bankaCoreClient.transferFunds(anyString(), any(TransferFundsRequest.class)))
                .thenReturn(new TransferFundsResponse(0L, 0L, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    @DisplayName("T8 UNIT: klijent investira uz FX konverziju i placa 1% komisiju")
    void invest_clientWithFxConversion_chargesOnePercentCommission() {
        Long fundId = 1L;
        Long clientId = 10L;
        Long sourceAccountId = 101L;
        Long fundAccountId = 201L;

        InvestmentFund fund = fund(fundId, fundAccountId, "Banka 2 Stable Income", new BigDecimal("1000"));

        // izvorni racun: licni klijentski, EUR, balans 1000
        InternalAccountDto sourceAccount = clientAccount(sourceAccountId, clientId, "EUR", new BigDecimal("1000.0000"));
        InternalAccountDto fundAccount = fundCashAccount(fundAccountId, "RSD", new BigDecimal("0.0000"));

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(sourceAccountId)).thenReturn(sourceAccount);
        when(bankaCoreClient.getAccount(fundAccountId)).thenReturn(fundAccount);
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(fundId, clientId, UserRole.CLIENT))
                .thenReturn(Optional.empty());

        // requested RSD, source EUR, klijent -> chargeFx=true
        when(currencyConversionService.convertForPurchase(
                eq(new BigDecimal("10000.0000")),
                eq("RSD"),
                eq("EUR"),
                eq(true)
        )).thenReturn(new CurrencyConversionService.ConversionResult(
                new BigDecimal("86.0000"),   // debitAmount
                new BigDecimal("1.0000"),    // commission
                new BigDecimal("0.008600"),
                new BigDecimal("0.008500")
        ));

        ClientFundPositionDto result = service.invest(
                fundId,
                new InvestFundDto(new BigDecimal("10000"), "RSD", sourceAccountId),
                clientId,
                UserRole.CLIENT
        );

        assertNotNull(result);
        assertEquals(fundId, result.getFundId());
        assertEquals(clientId, result.getUserId());
        assertEquals(UserRole.CLIENT, result.getUserRole());
        assertBd("10000.0000", result.getTotalInvested());

        // banka-core transfer: izvorni racun gubi debitAmount+commission (87 EUR),
        // fond dobija amountRsd (10000 RSD), banka dobija commission (1 EUR).
        ArgumentCaptor<TransferFundsRequest> reqCap = ArgumentCaptor.forClass(TransferFundsRequest.class);
        verify(bankaCoreClient).transferFunds(eq("fund-invest-1"), reqCap.capture());
        TransferFundsRequest req = reqCap.getValue();
        assertEquals(sourceAccountId, req.fromAccountId());
        assertBd("87.0000", req.debitAmount());
        assertEquals(fundAccountId, req.toAccountId());
        assertBd("10000.0000", req.creditAmount());
        assertBd("1.0000", req.commission());
        assertEquals("EUR", req.commissionCurrency());

        ArgumentCaptor<ClientFundTransaction> txCaptor = ArgumentCaptor.forClass(ClientFundTransaction.class);
        verify(clientFundTransactionRepository, atLeast(2)).save(txCaptor.capture());
        ClientFundTransaction lastTx = txCaptor.getAllValues().get(txCaptor.getAllValues().size() - 1);
        assertEquals(ClientFundTransactionStatus.COMPLETED, lastTx.getStatus());
        assertTrue(lastTx.isInflow());
        assertBd("10000.0000", lastTx.getAmountRsd());
    }

    @Test
    @DisplayName("T8 UNIT: supervizor investira u ime banke uz FX konverziju i placa 0% komisije")
    void invest_supervisorWithFxConversion_chargesZeroCommission() {
        Long fundId = 1L;
        Long supervisorId = 5L;
        Long bankClientId = 50L;
        Long sourceAccountId = 102L;
        Long fundAccountId = 202L;

        InvestmentFund fund = fund(fundId, fundAccountId, "Banka 2 Stable Income", new BigDecimal("1000"));

        // izvorni racun: bankin BANK_TRADING EUR (ownerClientId null), balans 1000
        InternalAccountDto sourceAccount = bankTradingAccount(sourceAccountId, "EUR", new BigDecimal("1000.0000"));
        InternalAccountDto fundAccount = fundCashAccount(fundAccountId, "RSD", new BigDecimal("0.0000"));

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(sourceAccountId)).thenReturn(sourceAccount);
        when(bankaCoreClient.getAccount(fundAccountId)).thenReturn(fundAccount);
        when(actuaryInfoRepository.findByEmployeeId(supervisorId)).thenReturn(Optional.of(supervisorInfo(supervisorId)));
        when(bankaCoreClient.getUserByEmail(BANK_EMAIL)).thenReturn(bankUser(bankClientId));
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(fundId, bankClientId, UserRole.CLIENT))
                .thenReturn(Optional.empty());

        // requested RSD, source EUR, supervizor -> chargeFx=false
        when(currencyConversionService.convertForPurchase(
                eq(new BigDecimal("10000.0000")),
                eq("RSD"),
                eq("EUR"),
                eq(false)
        )).thenReturn(new CurrencyConversionService.ConversionResult(
                new BigDecimal("85.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.008500"),
                new BigDecimal("0.008500")
        ));

        ClientFundPositionDto result = service.invest(
                fundId,
                new InvestFundDto(new BigDecimal("10000"), "RSD", sourceAccountId),
                supervisorId,
                UserRole.EMPLOYEE
        );

        assertNotNull(result);
        assertEquals(bankClientId, result.getUserId());
        assertEquals(UserRole.CLIENT, result.getUserRole());
        assertBd("10000.0000", result.getTotalInvested());

        // 0% komisija -> debitAmount == 85, commission == 0
        ArgumentCaptor<TransferFundsRequest> reqCap = ArgumentCaptor.forClass(TransferFundsRequest.class);
        verify(bankaCoreClient).transferFunds(eq("fund-invest-1"), reqCap.capture());
        TransferFundsRequest req = reqCap.getValue();
        assertBd("85.0000", req.debitAmount());
        assertBd("10000.0000", req.creditAmount());
        assertBd("0.0000", req.commission());
    }

    @Test
    @DisplayName("T8 UNIT: withdraw prolazi odmah kada fond ima dovoljno likvidnih sredstava")
    void withdraw_whenFundHasEnoughCash_completesImmediately() {
        Long fundId = 1L;
        Long clientId = 10L;
        Long fundAccountId = 201L;
        Long destinationAccountId = 101L;

        InvestmentFund fund = fund(fundId, fundAccountId, "Banka 2 Stable Income", new BigDecimal("1000"));
        InternalAccountDto fundAccount = fundCashAccount(fundAccountId, "RSD", new BigDecimal("10000.0000"));
        InternalAccountDto destinationAccount = clientAccount(destinationAccountId, clientId, "RSD", new BigDecimal("1000.0000"));

        ClientFundPosition position = position(1L, fundId, clientId, UserRole.CLIENT, new BigDecimal("10000.0000"));

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(fundAccountId)).thenReturn(fundAccount);
        when(bankaCoreClient.getAccount(destinationAccountId)).thenReturn(destinationAccount);
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(fundId, clientId, UserRole.CLIENT))
                .thenReturn(Optional.of(position));

        ClientFundTransactionDto result = service.withdraw(
                fundId,
                new WithdrawFundDto(new BigDecimal("5000"), destinationAccountId),
                clientId,
                UserRole.CLIENT
        );

        assertNotNull(result);
        assertEquals("COMPLETED", result.getStatus());
        assertFalse(result.isInflow());
        assertBd("5000.0000", result.getAmountRsd());

        // executePayout: fond gubi 5000 RSD, klijent dobija 5000 RSD (ista valuta,
        // bez FX provizije).
        ArgumentCaptor<TransferFundsRequest> reqCap = ArgumentCaptor.forClass(TransferFundsRequest.class);
        verify(bankaCoreClient).transferFunds(eq("fund-payout-1"), reqCap.capture());
        TransferFundsRequest req = reqCap.getValue();
        assertEquals(fundAccountId, req.fromAccountId());
        assertBd("5000.0000", req.debitAmount());
        assertEquals(destinationAccountId, req.toAccountId());
        assertBd("5000.0000", req.creditAmount());
        assertBd("0.0000", req.commission());

        verify(clientFundPositionRepository).save(argThat(p ->
                p.getTotalInvested().compareTo(new BigDecimal("5000.0000")) == 0
        ));
        verify(fundLiquidationService, never()).liquidateFor(anyLong(), any());
    }

    @Test
    @DisplayName("T8 UNIT: withdraw ide u PENDING i poziva T9 kada fond nema dovoljno cash-a")
    void withdraw_whenFundHasInsufficientCash_setsPendingAndCallsLiquidation() {
        Long fundId = 1L;
        Long clientId = 10L;
        Long fundAccountId = 201L;
        Long destinationAccountId = 101L;

        InvestmentFund fund = fund(fundId, fundAccountId, "Banka 2 Stable Income", new BigDecimal("1000"));
        InternalAccountDto fundAccount = fundCashAccount(fundAccountId, "RSD", new BigDecimal("1000.0000"));
        InternalAccountDto destinationAccount = clientAccount(destinationAccountId, clientId, "RSD", new BigDecimal("0.0000"));

        ClientFundPosition position = position(1L, fundId, clientId, UserRole.CLIENT, new BigDecimal("5000.0000"));

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(fundAccountId)).thenReturn(fundAccount);
        when(bankaCoreClient.getAccount(destinationAccountId)).thenReturn(destinationAccount);
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(fundId, clientId, UserRole.CLIENT))
                .thenReturn(Optional.of(position));

        ClientFundTransactionDto result = service.withdraw(
                fundId,
                new WithdrawFundDto(new BigDecimal("5000"), destinationAccountId),
                clientId,
                UserRole.CLIENT
        );

        assertNotNull(result);
        assertEquals("PENDING", result.getStatus());
        assertFalse(result.isInflow());
        assertBd("5000.0000", result.getAmountRsd());
        assertNotNull(result.getFailureReason());

        // Nedovoljno cash-a -> nema isplate (transferFunds), poziva se likvidacija
        // za shortfall = 5000 - 1000 = 4000.
        verify(bankaCoreClient, never()).transferFunds(anyString(), any());
        verify(clientFundPositionRepository).delete(position);
        verify(fundLiquidationService).liquidateFor(eq(fundId), argThat(amount ->
                amount.compareTo(new BigDecimal("4000.0000")) == 0
        ));
    }

    @Test
    @DisplayName("T8 UNIT: invest odbija uplatu ispod minimalnog uloga")
    void invest_whenAmountBelowMinimum_throwsIllegalArgumentException() {
        Long fundId = 1L;
        Long clientId = 10L;
        Long sourceAccountId = 101L;
        Long fundAccountId = 201L;

        InvestmentFund fund = fund(fundId, fundAccountId, "Banka 2 Stable Income", new BigDecimal("1000"));
        InternalAccountDto sourceAccount = clientAccount(sourceAccountId, clientId, "RSD", new BigDecimal("10000.0000"));
        InternalAccountDto fundAccount = fundCashAccount(fundAccountId, "RSD", BigDecimal.ZERO);

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(sourceAccountId)).thenReturn(sourceAccount);
        when(bankaCoreClient.getAccount(fundAccountId)).thenReturn(fundAccount);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.invest(
                        fundId,
                        new InvestFundDto(new BigDecimal("500"), "RSD", sourceAccountId),
                        clientId,
                        UserRole.CLIENT
                )
        );

        assertTrue(ex.getMessage().contains("najmanje"));
        verify(clientFundTransactionRepository, never()).save(any(ClientFundTransaction.class));
        verify(clientFundPositionRepository, never()).save(any(ClientFundPosition.class));
        verify(bankaCoreClient, never()).transferFunds(anyString(), any());
    }

    @Test
    @DisplayName("T8 UNIT: invest odbija kada klijent pokusa da koristi tudji racun")
    void invest_whenClientUsesAnotherClientAccount_throwsAccessDenied() {
        Long fundId = 1L;
        Long loggedClientId = 10L;
        Long otherClientId = 99L;
        Long sourceAccountId = 101L;
        Long fundAccountId = 201L;

        InvestmentFund fund = fund(fundId, fundAccountId, "Banka 2 Stable Income", new BigDecimal("1000"));
        // racun pripada DRUGOM klijentu (ownerClientId = 99)
        InternalAccountDto sourceAccount = clientAccount(sourceAccountId, otherClientId, "RSD", new BigDecimal("10000.0000"));
        InternalAccountDto fundAccount = fundCashAccount(fundAccountId, "RSD", BigDecimal.ZERO);

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(sourceAccountId)).thenReturn(sourceAccount);
        when(bankaCoreClient.getAccount(fundAccountId)).thenReturn(fundAccount);

        assertThrows(
                AccessDeniedException.class,
                () -> service.invest(
                        fundId,
                        new InvestFundDto(new BigDecimal("5000"), "RSD", sourceAccountId),
                        loggedClientId,
                        UserRole.CLIENT
                )
        );

        verify(clientFundTransactionRepository, never()).save(any(ClientFundTransaction.class));
        verify(clientFundPositionRepository, never()).save(any(ClientFundPosition.class));
        verify(bankaCoreClient, never()).transferFunds(anyString(), any());
    }

    @Test
    @DisplayName("T8 UNIT: invest odbija kada nema dovoljno sredstava na racunu")
    void invest_whenInsufficientFunds_throwsInsufficientFundsException() {
        Long fundId = 1L;
        Long clientId = 10L;
        Long sourceAccountId = 101L;
        Long fundAccountId = 201L;

        InvestmentFund fund = fund(fundId, fundAccountId, "Banka 2 Stable Income", new BigDecimal("1000"));
        InternalAccountDto sourceAccount = clientAccount(sourceAccountId, clientId, "RSD", new BigDecimal("500.0000"));
        InternalAccountDto fundAccount = fundCashAccount(fundAccountId, "RSD", BigDecimal.ZERO);

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(sourceAccountId)).thenReturn(sourceAccount);
        when(bankaCoreClient.getAccount(fundAccountId)).thenReturn(fundAccount);

        assertThrows(
                InsufficientFundsException.class,
                () -> service.invest(
                        fundId,
                        new InvestFundDto(new BigDecimal("1000"), "RSD", sourceAccountId),
                        clientId,
                        UserRole.CLIENT
                )
        );

        // pre-check vs availableBalance odbija pre nego sto se transfer pozove
        verify(clientFundTransactionRepository, never()).save(any(ClientFundTransaction.class));
        verify(bankaCoreClient, never()).transferFunds(anyString(), any());
    }

    @Test
    @DisplayName("T8 UNIT: invest mapira banka-core 409 u InsufficientFundsException")
    void invest_whenBankaCoreReturns409_throwsInsufficientFundsException() {
        Long fundId = 1L;
        Long clientId = 10L;
        Long sourceAccountId = 101L;
        Long fundAccountId = 201L;

        InvestmentFund fund = fund(fundId, fundAccountId, "Banka 2 Stable Income", new BigDecimal("1000"));
        // availableBalance prolazi lokalni pre-check (10000 > 5000) ali banka-core
        // svejedno odbija (npr. promenjeno stanje izmedju citanja i transfera).
        InternalAccountDto sourceAccount = clientAccount(sourceAccountId, clientId, "RSD", new BigDecimal("10000.0000"));
        InternalAccountDto fundAccount = fundCashAccount(fundAccountId, "RSD", BigDecimal.ZERO);

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(sourceAccountId)).thenReturn(sourceAccount);
        when(bankaCoreClient.getAccount(fundAccountId)).thenReturn(fundAccount);
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(fundId, clientId, UserRole.CLIENT))
                .thenReturn(Optional.empty());
        when(bankaCoreClient.transferFunds(eq("fund-invest-1"), any(TransferFundsRequest.class)))
                .thenThrow(new BankaCoreClientException(409, "banka-core 409"));

        assertThrows(
                InsufficientFundsException.class,
                () -> service.invest(
                        fundId,
                        new InvestFundDto(new BigDecimal("5000"), "RSD", sourceAccountId),
                        clientId,
                        UserRole.CLIENT
                )
        );
    }

    @Test
    @DisplayName("T8 UNIT: invest odbija kada sourceAccount i fundAccount imaju isti ID")
    void invest_whenSourceAccountIsFundAccount_throwsIllegalArgumentException() {
        Long fundId = 1L;
        Long clientId = 10L;
        Long sameAccountId = 201L;

        InvestmentFund fund = fund(fundId, sameAccountId, "Banka 2 Stable Income", new BigDecimal("1000"));
        InternalAccountDto sameAccount = clientAccount(sameAccountId, clientId, "RSD", new BigDecimal("10000.0000"));

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(sameAccountId)).thenReturn(sameAccount);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.invest(
                        fundId,
                        new InvestFundDto(new BigDecimal("5000"), "RSD", sameAccountId),
                        clientId,
                        UserRole.CLIENT
                )
        );

        assertTrue(ex.getMessage().contains("ne moze biti isti"));
        verify(clientFundTransactionRepository, never()).save(any(ClientFundTransaction.class));
        verify(bankaCoreClient, never()).transferFunds(anyString(), any());
    }

    @Test
    @DisplayName("T8 UNIT: withdraw odbija kada je trazeni iznos veci od pozicije")
    void withdraw_whenRequestedAmountGreaterThanPosition_throwsIllegalArgumentException() {
        Long fundId = 1L;
        Long clientId = 10L;
        Long fundAccountId = 201L;
        Long destinationAccountId = 101L;

        InvestmentFund fund = fund(fundId, fundAccountId, "Banka 2 Stable Income", new BigDecimal("1000"));
        InternalAccountDto fundAccount = fundCashAccount(fundAccountId, "RSD", new BigDecimal("10000.0000"));
        InternalAccountDto destinationAccount = clientAccount(destinationAccountId, clientId, "RSD", BigDecimal.ZERO);

        ClientFundPosition position = position(1L, fundId, clientId, UserRole.CLIENT, new BigDecimal("3000.0000"));

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(fundAccountId)).thenReturn(fundAccount);
        when(bankaCoreClient.getAccount(destinationAccountId)).thenReturn(destinationAccount);
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(fundId, clientId, UserRole.CLIENT))
                .thenReturn(Optional.of(position));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.withdraw(
                        fundId,
                        new WithdrawFundDto(new BigDecimal("5000"), destinationAccountId),
                        clientId,
                        UserRole.CLIENT
                )
        );

        assertTrue(ex.getMessage().contains("veci od pozicije"));
        verify(clientFundTransactionRepository, never()).save(any(ClientFundTransaction.class));
        verify(fundLiquidationService, never()).liquidateFor(anyLong(), any());
    }

    @Test
    @DisplayName("T8 UNIT: withdraw sa null amount povlaci celu poziciju")
    void withdraw_whenAmountIsNull_redeemsEntirePosition() {
        Long fundId = 1L;
        Long clientId = 10L;
        Long fundAccountId = 201L;
        Long destinationAccountId = 101L;

        InvestmentFund fund = fund(fundId, fundAccountId, "Banka 2 Stable Income", new BigDecimal("1000"));
        InternalAccountDto fundAccount = fundCashAccount(fundAccountId, "RSD", new BigDecimal("10000.0000"));
        InternalAccountDto destinationAccount = clientAccount(destinationAccountId, clientId, "RSD", new BigDecimal("1000.0000"));

        ClientFundPosition position = position(1L, fundId, clientId, UserRole.CLIENT, new BigDecimal("5000.0000"));

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(fundAccountId)).thenReturn(fundAccount);
        when(bankaCoreClient.getAccount(destinationAccountId)).thenReturn(destinationAccount);
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(fundId, clientId, UserRole.CLIENT))
                .thenReturn(Optional.of(position));

        ClientFundTransactionDto result = service.withdraw(
                fundId,
                new WithdrawFundDto(null, destinationAccountId),
                clientId,
                UserRole.CLIENT
        );

        assertNotNull(result);
        assertEquals("COMPLETED", result.getStatus());
        assertBd("5000.0000", result.getAmountRsd());

        verify(bankaCoreClient).transferFunds(eq("fund-payout-1"), any(TransferFundsRequest.class));
        verify(clientFundPositionRepository).delete(position);
        verify(fundLiquidationService, never()).liquidateFor(anyLong(), any());
    }

    @Test
    @DisplayName("T8 UNIT: withdraw na devizni racun klijenta naplacuje 1% FX proviziju")
    void withdraw_clientToForeignAccount_chargesOnePercentFxFee() {
        Long fundId = 1L;
        Long clientId = 10L;
        Long fundAccountId = 201L;
        Long destinationAccountId = 101L;

        InvestmentFund fund = fund(fundId, fundAccountId, "Banka 2 Stable Income", new BigDecimal("1000"));
        InternalAccountDto fundAccount = fundCashAccount(fundAccountId, "RSD", new BigDecimal("10000.0000"));
        // devizni licni klijentski racun
        InternalAccountDto destinationAccount = clientAccount(destinationAccountId, clientId, "EUR", BigDecimal.ZERO);

        ClientFundPosition position = position(1L, fundId, clientId, UserRole.CLIENT, new BigDecimal("5000.0000"));

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(fundAccountId)).thenReturn(fundAccount);
        when(bankaCoreClient.getAccount(destinationAccountId)).thenReturn(destinationAccount);
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(fundId, clientId, UserRole.CLIENT))
                .thenReturn(Optional.of(position));
        when(currencyConversionService.convert(new BigDecimal("5000.0000"), "RSD", "EUR"))
                .thenReturn(new BigDecimal("42.5000"));

        ClientFundTransactionDto result = service.withdraw(
                fundId,
                new WithdrawFundDto(new BigDecimal("5000"), destinationAccountId),
                clientId,
                UserRole.CLIENT
        );

        assertNotNull(result);
        assertEquals("COMPLETED", result.getStatus());
        assertBd("5000.0000", result.getAmountRsd());

        // grossCredit = 42.5 EUR, fxFee = 1% = 0.4250, netCredit = 42.0750.
        ArgumentCaptor<TransferFundsRequest> reqCap = ArgumentCaptor.forClass(TransferFundsRequest.class);
        verify(bankaCoreClient).transferFunds(eq("fund-payout-1"), reqCap.capture());
        TransferFundsRequest req = reqCap.getValue();
        assertBd("5000.0000", req.debitAmount());
        assertBd("42.0750", req.creditAmount());
        assertBd("0.4250", req.commission());
        assertEquals("EUR", req.commissionCurrency());
    }

    @Test
    @DisplayName("T8 UNIT: supervizor withdraw na devizni bankin racun ne placa FX proviziju")
    void withdraw_supervisorToForeignBankAccount_chargesZeroFxFee() {
        Long fundId = 1L;
        Long supervisorId = 5L;
        Long bankClientId = 50L;
        Long fundAccountId = 201L;
        Long destinationAccountId = 102L;

        InvestmentFund fund = fund(fundId, fundAccountId, "Banka 2 Stable Income", new BigDecimal("1000"));
        InternalAccountDto fundAccount = fundCashAccount(fundAccountId, "RSD", new BigDecimal("10000.0000"));
        // devizni bankin BANK_TRADING racun (ownerClientId null)
        InternalAccountDto destinationAccount = bankTradingAccount(destinationAccountId, "EUR", BigDecimal.ZERO);

        ClientFundPosition position = position(1L, fundId, bankClientId, UserRole.CLIENT, new BigDecimal("5000.0000"));

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(fundAccountId)).thenReturn(fundAccount);
        when(bankaCoreClient.getAccount(destinationAccountId)).thenReturn(destinationAccount);
        when(actuaryInfoRepository.findByEmployeeId(supervisorId)).thenReturn(Optional.of(supervisorInfo(supervisorId)));
        when(bankaCoreClient.getUserByEmail(BANK_EMAIL)).thenReturn(bankUser(bankClientId));
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(fundId, bankClientId, UserRole.CLIENT))
                .thenReturn(Optional.of(position));
        when(currencyConversionService.convert(new BigDecimal("5000.0000"), "RSD", "EUR"))
                .thenReturn(new BigDecimal("42.5000"));

        ClientFundTransactionDto result = service.withdraw(
                fundId,
                new WithdrawFundDto(new BigDecimal("5000"), destinationAccountId),
                supervisorId,
                UserRole.EMPLOYEE
        );

        assertNotNull(result);
        assertEquals("COMPLETED", result.getStatus());
        assertBd("5000.0000", result.getAmountRsd());

        // supervizor -> bez FX provizije: netCredit == grossCredit == 42.5, commission 0.
        ArgumentCaptor<TransferFundsRequest> reqCap = ArgumentCaptor.forClass(TransferFundsRequest.class);
        verify(bankaCoreClient).transferFunds(eq("fund-payout-1"), reqCap.capture());
        TransferFundsRequest req = reqCap.getValue();
        assertBd("5000.0000", req.debitAmount());
        assertBd("42.5000", req.creditAmount());
        assertBd("0.0000", req.commission());
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private InvestmentFund fund(Long id, Long accountId, String name, BigDecimal minimumContribution) {
        InvestmentFund fund = new InvestmentFund();
        fund.setId(id);
        fund.setName(name);
        fund.setDescription("Test fond");
        fund.setMinimumContribution(minimumContribution);
        fund.setManagerEmployeeId(5L);
        fund.setAccountId(accountId);
        fund.setCreatedAt(LocalDateTime.now());
        fund.setActive(true);
        return fund;
    }

    /** Licni klijentski racun — ownerClientId = clientId, kategorija CLIENT. */
    private InternalAccountDto clientAccount(Long id, Long clientId, String currency, BigDecimal amount) {
        return new InternalAccountDto(id, "2220001123456789" + id, "Klijent",
                amount, amount, BigDecimal.ZERO, currency, "ACTIVE",
                clientId, null, "CLIENT");
    }

    /** Bankin BANK_TRADING racun — ownerClientId null, kategorija BANK_TRADING. */
    private InternalAccountDto bankTradingAccount(Long id, String currency, BigDecimal amount) {
        return new InternalAccountDto(id, "222000100000000" + id, "Banka 2 d.o.o.",
                amount, amount, BigDecimal.ZERO, currency, "ACTIVE",
                null, null, "BANK_TRADING");
    }

    /** Gotovinski FUND racun fonda — ownerClientId null, kategorija FUND. */
    private InternalAccountDto fundCashAccount(Long id, String currency, BigDecimal amount) {
        return new InternalAccountDto(id, "222000100000000" + id, "Banka 2 d.o.o.",
                amount, amount, BigDecimal.ZERO, currency, "ACTIVE",
                null, null, "FUND");
    }

    private InternalUserDto bankUser(Long id) {
        return new InternalUserDto(id, "CLIENT", BANK_EMAIL, "Banka 2", "d.o.o.", true, null);
    }

    private ActuaryInfo supervisorInfo(Long employeeId) {
        ActuaryInfo info = new ActuaryInfo();
        info.setId(1L);
        info.setEmployeeId(employeeId);
        info.setActuaryType(ActuaryType.SUPERVISOR);
        info.setNeedApproval(false);
        return info;
    }

    private ClientFundPosition position(Long id, Long fundId, Long userId, String userRole, BigDecimal totalInvested) {
        ClientFundPosition position = new ClientFundPosition();
        position.setId(id);
        position.setFundId(fundId);
        position.setUserId(userId);
        position.setUserRole(userRole);
        position.setTotalInvested(totalInvested);
        position.setLastModifiedAt(LocalDateTime.now());
        return position;
    }

    private void assertBd(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
