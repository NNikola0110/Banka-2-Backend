package rs.raf.trading.order.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2.contracts.internal.CommitFundsRequest;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.exception.InsufficientHoldingsException;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.SagaState;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test {@link FundReservationService} — money-seam adaptacija monolitnog testa
 * (faza 2c). Monolitna verzija je direktno menjala {@code Account.balance} i
 * {@code Account.reservedAmount}; trading-service verzija delegira novcanu nogu
 * banka-core internom seam-u ({@code /internal/funds/reserve|commit|release}).
 *
 * Zbog toga su BUY asercije pomerene: umesto da proveravaju {@code Account}
 * stanje, proveravaju da je {@link BankaCoreClient} pozvan sa ispravnim
 * argumentima i deterministickim idempotency kljucem
 * ({@code order-{id}-reserve}, {@code order-{id}-fill-{seq}},
 * {@code order-{id}-release}). Pro-rata matematika je sada banka-core posao
 * pa se ne testira lokalno. SELL operacije diraju samo lokalni {@link Portfolio}
 * i kopirane su verbatim.
 */
@ExtendWith(MockitoExtension.class)
class FundReservationServiceTest {

    @Mock
    BankaCoreClient bankaCoreClient;

    @Mock
    PortfolioRepository portfolioRepository;

    @InjectMocks
    FundReservationService service;

    // ── BUY helpers ──────────────────────────────────────────────────────────
    private Order buyOrder(BigDecimal reservedAmount, Integer qty) {
        Order o = new Order();
        o.setId(100L);
        o.setReservedAmount(reservedAmount);
        o.setReservedAccountId(1L);
        o.setQuantity(qty);
        o.setRemainingPortions(qty);
        o.setReservationReleased(false);
        return o;
    }

    private InternalAccountDto account(String currency) {
        return new InternalAccountDto(1L, "111", "Owner",
                new BigDecimal("10000"), new BigDecimal("10000"), BigDecimal.ZERO,
                currency, "ACTIVE", 1L, null, "CLIENT");
    }

    private ReserveFundsResponse reserveResponse(String reservationId) {
        return new ReserveFundsResponse(reservationId, 1L,
                new BigDecimal("2500"), new BigDecimal("7500"));
    }

    // ── reserveForBuy ────────────────────────────────────────────────────────
    @Test
    void reserveForBuy_callsBankaCoreReserve_withDeterministicKey_andStoresReservationId() {
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        when(bankaCoreClient.getAccount(1L)).thenReturn(account("RSD"));
        when(bankaCoreClient.reserveFunds(eq("order-100-reserve"), any(ReserveFundsRequest.class)))
                .thenReturn(reserveResponse("res-001"));

        service.reserveForBuy(order);

        ArgumentCaptor<ReserveFundsRequest> reqCap = ArgumentCaptor.forClass(ReserveFundsRequest.class);
        verify(bankaCoreClient).reserveFunds(eq("order-100-reserve"), reqCap.capture());
        assertThat(reqCap.getValue().accountId()).isEqualTo(1L);
        assertThat(reqCap.getValue().amount()).isEqualByComparingTo("2500.00");
        assertThat(reqCap.getValue().currencyCode()).isEqualTo("RSD");
        assertThat(order.getBankaCoreReservationId()).isEqualTo("res-001");
        assertThat(order.getSagaState()).isEqualTo(SagaState.FUNDS_RESERVED);
    }

    @Test
    void reserveForBuy_throwsInsufficientFundsException_whenBankaCoreReturns409() {
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        when(bankaCoreClient.getAccount(1L)).thenReturn(account("RSD"));
        when(bankaCoreClient.reserveFunds(eq("order-100-reserve"), any(ReserveFundsRequest.class)))
                .thenThrow(new BankaCoreClientException(409, "banka-core 409"));

        assertThatThrownBy(() -> service.reserveForBuy(order))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void reserveForBuy_throwsIllegalStateException_whenReservationAlreadyReleased() {
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        order.setReservationReleased(true);

        assertThatThrownBy(() -> service.reserveForBuy(order))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(bankaCoreClient);
    }

    @Test
    void reserveForBuy_throwsIllegalArgument_whenReservedAmountNull() {
        Order order = buyOrder(null, 10);

        assertThatThrownBy(() -> service.reserveForBuy(order))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pozitivan");
        verifyNoInteractions(bankaCoreClient);
    }

    @Test
    void reserveForBuy_throwsIllegalArgument_whenReservedAmountZero() {
        Order order = buyOrder(BigDecimal.ZERO, 10);

        assertThatThrownBy(() -> service.reserveForBuy(order))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reserveForBuy_throwsIllegalArgument_whenReservedAmountNegative() {
        Order order = buyOrder(new BigDecimal("-5.00"), 10);

        assertThatThrownBy(() -> service.reserveForBuy(order))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reserveForBuy_throwsIllegalArgument_whenReservedAccountIdNull() {
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        order.setReservedAccountId(null);

        assertThatThrownBy(() -> service.reserveForBuy(order))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── releaseForBuy ────────────────────────────────────────────────────────
    @Test
    void releaseForBuy_callsBankaCoreRelease_withDeterministicKey_andSetsReleasedFlag() {
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        order.setBankaCoreReservationId("res-001");

        service.releaseForBuy(order);

        verify(bankaCoreClient).releaseFunds(eq("res-001"), eq("order-100-release"), any());
        assertThat(order.isReservationReleased()).isTrue();
        assertThat(order.getSagaState()).isEqualTo(SagaState.COMPENSATED);
    }

    @Test
    void releaseForBuy_isIdempotent_whenAlreadyReleased() {
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        order.setReservationReleased(true);

        service.releaseForBuy(order);

        verifyNoInteractions(bankaCoreClient);
        assertThat(order.isReservationReleased()).isTrue();
    }

    @Test
    void releaseForBuy_marksReleased_whenReservedAccountIdIsNull() {
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        order.setReservedAccountId(null);
        order.setBankaCoreReservationId("res-001");

        service.releaseForBuy(order);

        assertThat(order.isReservationReleased()).isTrue();
        verifyNoInteractions(bankaCoreClient);
    }

    @Test
    void releaseForBuy_marksReleased_whenReservedAmountIsNull() {
        Order order = buyOrder(null, 10);
        order.setBankaCoreReservationId("res-001");

        service.releaseForBuy(order);

        assertThat(order.isReservationReleased()).isTrue();
        verifyNoInteractions(bankaCoreClient);
    }

    @Test
    void releaseForBuy_marksReleased_whenBankaCoreReservationIdIsNull() {
        // Nema banka-core rezervacije -> nista za osloboditi, samo flag.
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        order.setBankaCoreReservationId(null);

        service.releaseForBuy(order);

        assertThat(order.isReservationReleased()).isTrue();
        verifyNoInteractions(bankaCoreClient);
    }

    @Test
    void releaseForBuy_isIdempotent_whenCalledTwice() {
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        order.setBankaCoreReservationId("res-001");

        service.releaseForBuy(order);
        service.releaseForBuy(order); // drugi poziv izlazi odmah zbog flag-a

        verify(bankaCoreClient).releaseFunds(eq("res-001"), eq("order-100-release"), any());
        assertThat(order.isReservationReleased()).isTrue();
    }

    // ── consumeForBuyFill ────────────────────────────────────────────────────
    @Test
    void consumeForBuyFill_callsBankaCoreCommit_withFillPrice_commission_andDeterministicSeqKey() {
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        order.setBankaCoreReservationId("res-001");
        order.setRemainingPortions(10); // jos nije bilo fill-a → fillSeq = 0

        service.consumeForBuyFill(order, 4, new BigDecimal("1000.00"), new BigDecimal("7.00"));

        ArgumentCaptor<CommitFundsRequest> reqCap = ArgumentCaptor.forClass(CommitFundsRequest.class);
        verify(bankaCoreClient).commitFunds(eq("res-001"), eq("order-100-fill-0"), reqCap.capture());
        assertThat(reqCap.getValue().amount()).isEqualByComparingTo("1000.00");
        assertThat(reqCap.getValue().commission()).isEqualByComparingTo("7.00");
        assertThat(reqCap.getValue().beneficiaryAccountId()).isNull();
    }

    @Test
    void consumeForBuyFill_secondFill_usesIncrementedSeqKey() {
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        order.setBankaCoreReservationId("res-001");
        order.setRemainingPortions(6); // 4 vec fill-ovano → fillSeq = 10 - 6 = 4

        service.consumeForBuyFill(order, 3, new BigDecimal("750.00"), new BigDecimal("5.00"));

        verify(bankaCoreClient).commitFunds(eq("res-001"), eq("order-100-fill-4"), any(CommitFundsRequest.class));
    }

    @Test
    void consumeForBuyFill_nullCommission_treatedAsZero() {
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        order.setBankaCoreReservationId("res-001");
        order.setRemainingPortions(10);

        service.consumeForBuyFill(order, 5, new BigDecimal("1000.00"), null);

        ArgumentCaptor<CommitFundsRequest> reqCap = ArgumentCaptor.forClass(CommitFundsRequest.class);
        verify(bankaCoreClient).commitFunds(eq("res-001"), eq("order-100-fill-0"), reqCap.capture());
        assertThat(reqCap.getValue().commission()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void consumeForBuyFill_throwsIllegalState_whenAlreadyReleased() {
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        order.setReservationReleased(true);

        assertThatThrownBy(() -> service.consumeForBuyFill(order, 5, new BigDecimal("1000.00"), BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(bankaCoreClient);
    }

    @Test
    void consumeForBuyFill_throwsIllegalArgument_whenQtyZero() {
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        order.setBankaCoreReservationId("res-001");

        assertThatThrownBy(() -> service.consumeForBuyFill(order, 0, new BigDecimal("1000.00"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(bankaCoreClient);
    }

    @Test
    void consumeForBuyFill_throwsIllegalArgument_whenQtyNegative() {
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        order.setBankaCoreReservationId("res-001");

        assertThatThrownBy(() -> service.consumeForBuyFill(order, -3, new BigDecimal("1000.00"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consumeForBuyFill_throwsIllegalState_whenNoReservationId() {
        Order order = buyOrder(new BigDecimal("2500.00"), 10);
        order.setBankaCoreReservationId(null);

        assertThatThrownBy(() -> service.consumeForBuyFill(order, 4, new BigDecimal("1000.00"), BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(bankaCoreClient);
    }

    // ── reserveForSell ───────────────────────────────────────────────────────
    private Portfolio portfolio(int quantity, int reserved) {
        Portfolio p = new Portfolio();
        p.setId(1L);
        p.setUserId(42L);
        p.setListingId(7L);
        p.setQuantity(quantity);
        p.setReservedQuantity(reserved);
        return p;
    }

    @Test
    void reserveForSell_increasesReservedQuantity() {
        Portfolio p = portfolio(30, 0);
        Order order = new Order();
        order.setId(200L);
        order.setQuantity(5);
        order.setRemainingPortions(5);

        service.reserveForSell(order, p);

        assertThat(p.getReservedQuantity()).isEqualTo(5);
        assertThat(p.getAvailableQuantity()).isEqualTo(25);
        verify(portfolioRepository).save(p);
    }

    @Test
    void reserveForSell_throwsInsufficientHoldings_whenAvailableQuantityTooLow() {
        Portfolio p = portfolio(30, 27);
        Order order = new Order();
        order.setQuantity(5);

        assertThatThrownBy(() -> service.reserveForSell(order, p))
                .isInstanceOf(InsufficientHoldingsException.class);

        verify(portfolioRepository, never()).save(any());
    }

    @Test
    void reserveForSell_throwsIllegalState_whenAlreadyReleased() {
        Portfolio p = portfolio(30, 0);
        Order order = new Order();
        order.setId(300L);
        order.setQuantity(5);
        order.setReservationReleased(true);

        assertThatThrownBy(() -> service.reserveForSell(order, p))
                .isInstanceOf(IllegalStateException.class);

        verify(portfolioRepository, never()).save(any());
    }

    @Test
    void reserveForSell_throwsIllegalArgument_whenQuantityZero() {
        Portfolio p = portfolio(30, 0);
        Order order = new Order();
        order.setQuantity(0);

        assertThatThrownBy(() -> service.reserveForSell(order, p))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reserveForSell_throwsIllegalArgument_whenQuantityNegative() {
        Portfolio p = portfolio(30, 0);
        Order order = new Order();
        order.setQuantity(-2);

        assertThatThrownBy(() -> service.reserveForSell(order, p))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── releaseForSell ───────────────────────────────────────────────────────
    @Test
    void releaseForSell_decreasesReservedQuantity_setsReleasedFlag() {
        Portfolio p = portfolio(30, 5);
        Order order = new Order();
        order.setId(201L);
        order.setQuantity(5);
        order.setRemainingPortions(5);
        order.setReservationReleased(false);

        service.releaseForSell(order, p);

        assertThat(p.getReservedQuantity()).isEqualTo(0);
        assertThat(order.isReservationReleased()).isTrue();
        verify(portfolioRepository).save(p);
    }

    @Test
    void releaseForSell_isIdempotent() {
        Portfolio p = portfolio(30, 5);
        Order order = new Order();
        order.setQuantity(5);
        order.setRemainingPortions(5);
        order.setReservationReleased(true);

        service.releaseForSell(order, p);

        assertThat(p.getReservedQuantity()).isEqualTo(5);
        verify(portfolioRepository, never()).save(any());
    }

    @Test
    void releaseForSell_usesRemainingPortions_whenNotNull() {
        Portfolio p = portfolio(30, 10);
        Order order = new Order();
        order.setQuantity(10);
        order.setRemainingPortions(3); // 7 vec fill-ovano → oslobodi samo 3
        order.setReservationReleased(false);

        service.releaseForSell(order, p);

        assertThat(p.getReservedQuantity()).isEqualTo(7);
        assertThat(order.isReservationReleased()).isTrue();
    }

    @Test
    void releaseForSell_fallsBackToQuantity_whenRemainingPortionsNull() {
        Portfolio p = portfolio(30, 5);
        Order order = new Order();
        order.setQuantity(5);
        order.setRemainingPortions(null);
        order.setReservationReleased(false);

        service.releaseForSell(order, p);

        assertThat(p.getReservedQuantity()).isEqualTo(0);
        assertThat(order.isReservationReleased()).isTrue();
        verify(portfolioRepository).save(p);
    }

    @Test
    void releaseForSell_clampsToZero_whenReservedLessThanToRelease() {
        Portfolio p = portfolio(30, 2);
        Order order = new Order();
        order.setQuantity(5);
        order.setRemainingPortions(5);
        order.setReservationReleased(false);

        service.releaseForSell(order, p);

        assertThat(p.getReservedQuantity()).isEqualTo(0);
    }

    // ── consumeForSellFill ───────────────────────────────────────────────────
    @Test
    void consumeForSellFill_reducesQuantityAndReservedProportionally() {
        Portfolio p = portfolio(30, 5);
        Order order = new Order();
        order.setId(202L);
        order.setQuantity(5);
        order.setRemainingPortions(5);

        service.consumeForSellFill(order, p, 2);

        assertThat(p.getQuantity()).isEqualTo(28);
        assertThat(p.getReservedQuantity()).isEqualTo(3);
        verify(portfolioRepository).save(p);
    }

    @Test
    void consumeForSellFill_throwsIllegalState_whenAlreadyReleased() {
        Portfolio p = portfolio(30, 5);
        Order order = new Order();
        order.setQuantity(5);
        order.setReservationReleased(true);

        assertThatThrownBy(() -> service.consumeForSellFill(order, p, 2))
                .isInstanceOf(IllegalStateException.class);

        verify(portfolioRepository, never()).save(any());
    }

    @Test
    void consumeForSellFill_throwsIllegalArgument_whenQtyZero() {
        Portfolio p = portfolio(30, 5);
        Order order = new Order();
        order.setQuantity(5);

        assertThatThrownBy(() -> service.consumeForSellFill(order, p, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consumeForSellFill_throwsIllegalArgument_whenQtyNegative() {
        Portfolio p = portfolio(30, 5);
        Order order = new Order();
        order.setQuantity(5);

        assertThatThrownBy(() -> service.consumeForSellFill(order, p, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consumeForSellFill_clampsReservedToZero_whenQtyBiggerThanReserved() {
        Portfolio p = portfolio(30, 2);
        Order order = new Order();
        order.setQuantity(10);
        order.setRemainingPortions(10);

        service.consumeForSellFill(order, p, 5);

        assertThat(p.getQuantity()).isEqualTo(25);
        assertThat(p.getReservedQuantity()).isEqualTo(0);
        verify(portfolioRepository).save(p);
    }
}
