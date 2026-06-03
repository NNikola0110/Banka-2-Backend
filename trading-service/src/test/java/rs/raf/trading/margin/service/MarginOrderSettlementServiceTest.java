package rs.raf.trading.margin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.CreditFundsResponse;
import rs.raf.banka2.contracts.internal.DebitFundsRequest;
import rs.raf.banka2.contracts.internal.DebitFundsResponse;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.margin.event.MarginAccountBlockedEvent;
import rs.raf.trading.margin.model.MarginAccount;
import rs.raf.trading.margin.model.MarginAccountStatus;
import rs.raf.trading.margin.model.MarginTransaction;
import rs.raf.trading.margin.model.UserMarginAccount;
import rs.raf.trading.margin.repository.MarginAccountRepository;
import rs.raf.trading.margin.repository.MarginTransactionRepository;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-STK-05 (25.05.2026): Unit testovi za margin BUY/SELL split logiku.
 *
 * <p>Pokrivenost po Marzni_Racuni.txt §75-123:
 * <ul>
 *   <li>BUY happy path: bankPart += LoanValue + debit bankin racun;
 *       userPart -= IM (rezervacija konzumirana).</li>
 *   <li>BUY insufficient IM → reject.</li>
 *   <li>SELL happy path: bankPart -= LoanValue (floor 0) + credit bankin;
 *       userPart += IM.</li>
 *   <li>SELL na racunu sa LoanValue=0 → visak ide na userPart.</li>
 *   <li>Margin call: IM padne ispod MM → status BLOCKED + event publish.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MarginOrderSettlementServiceTest {

    @Mock
    private MarginAccountRepository marginAccountRepository;
    @Mock
    private MarginTransactionRepository marginTransactionRepository;
    @Mock
    private BankaCoreClient bankaCoreClient;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private MarginOrderSettlementService service;

    @BeforeEach
    void setUp() {
        service = new MarginOrderSettlementService(
                marginAccountRepository, marginTransactionRepository,
                bankaCoreClient, eventPublisher);
    }

    // ── BUY happy path ──────────────────────────────────────────────────────

    @Test
    void settleMarginBuyFill_happyPath_splitsTotalIntoBankAndUserParts() {
        // Given: margin sa BP=0.5, IM=20000, MM=5000, LV=0, reservedMargin=2500 (od reservation).
        UserMarginAccount margin = userMargin(50L, 10L, "20000", "5000", "0.50", "0",
                "2500", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));
        when(bankaCoreClient.getBankTradingAccount("RSD")).thenReturn(bankAccount(999L, "RSD"));
        when(bankaCoreClient.debitFunds(anyString(), any(DebitFundsRequest.class)))
                .thenReturn(new DebitFundsResponse(999L, new BigDecimal("100000"), new BigDecimal("900000")));

        Order order = marginBuyOrder(100L, 10L, "5000"); // total = 5000

        // When: settle fill for total = 5000 (full fill = 10 kom, approx==actual).
        service.settleMarginBuyFill(order, 0, 10, new BigDecimal("5000"));

        // Then: bankPart = 5000*0.5 = 2500; userPart = 2500.
        // IM: 20000 - 2500 = 17500. reservedMargin: 2500 - 2500 = 0. Loan: 0 + 2500 = 2500.
        assertThat(margin.getInitialMargin()).isEqualByComparingTo("17500");
        assertThat(margin.getReservedMargin()).isEqualByComparingTo("0");
        assertThat(margin.getLoanValue()).isEqualByComparingTo("2500");
        assertThat(margin.getStatus()).isEqualTo(MarginAccountStatus.ACTIVE);

        // Bank trading account debit-ovan za bankPart.
        ArgumentCaptor<DebitFundsRequest> debitCap = ArgumentCaptor.forClass(DebitFundsRequest.class);
        verify(bankaCoreClient).debitFunds(eq("margin-buy-100-fill-0"), debitCap.capture());
        assertThat(debitCap.getValue().amount()).isEqualByComparingTo("2500");
        assertThat(debitCap.getValue().accountId()).isEqualTo(999L);

        // Audit BUY transakcija.
        ArgumentCaptor<MarginTransaction> txCap = ArgumentCaptor.forClass(MarginTransaction.class);
        verify(marginTransactionRepository).save(txCap.capture());
        assertThat(txCap.getValue().getAmount()).isEqualByComparingTo("5000");
    }

    // ── BUY insufficient initialMargin → reject ─────────────────────────────

    @Test
    void reserveForMarginBuy_insufficientMargin_returnsFalse_noChange() {
        // Margin sa IM=1000, reservedMargin=500, available=500. BUY zahteva userPart=2500.
        UserMarginAccount margin = userMargin(50L, 10L, "1000", "300", "0.50", "0",
                "500", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));

        Order order = marginBuyOrder(101L, 10L, "5000"); // total=5000, userPart=2500

        boolean result = service.reserveForMarginBuy(order, new BigDecimal("5000"));

        assertThat(result).isFalse();
        // Mutacije ne smeju biti perzistirane.
        verify(marginAccountRepository, never()).save(margin);
    }

    @Test
    void reserveForMarginBuy_sufficient_addsToReservedMargin() {
        UserMarginAccount margin = userMargin(50L, 10L, "20000", "5000", "0.50", "0",
                "0", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));

        Order order = marginBuyOrder(102L, 10L, "5000");
        boolean result = service.reserveForMarginBuy(order, new BigDecimal("5000"));

        assertThat(result).isTrue();
        // userPart = 2500 (= 5000 * (1 - 0.5)) → reservedMargin postaje 2500.
        assertThat(margin.getReservedMargin()).isEqualByComparingTo("2500");
        verify(marginAccountRepository).save(margin);
    }

    // ── SELL happy path ─────────────────────────────────────────────────────

    @Test
    void settleMarginSellFill_happyPath_repaysLoanAndIncreasesIM() {
        // Margin sa BP=0.5, IM=10000, MM=5000, LV=3000.
        UserMarginAccount margin = userMargin(50L, 10L, "10000", "5000", "0.50", "3000",
                "0", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));
        when(bankaCoreClient.getBankTradingAccount("RSD")).thenReturn(bankAccount(999L, "RSD"));
        when(bankaCoreClient.creditFunds(anyString(), any(CreditFundsRequest.class)))
                .thenReturn(new CreditFundsResponse(999L, new BigDecimal("105000"), new BigDecimal("1105000")));

        Order order = marginSellOrder(200L, 10L);

        BigDecimal userPart = service.settleMarginSellFill(order, 0, new BigDecimal("4000"));

        // bankPart = 4000 * 0.5 = 2000 (<= loan=3000, regular)
        // userPart = 4000 - 2000 = 2000.
        // newLoan = 3000 - 2000 = 1000.
        // newIM = 10000 + 2000 = 12000.
        assertThat(userPart).isEqualByComparingTo("2000");
        assertThat(margin.getLoanValue()).isEqualByComparingTo("1000");
        assertThat(margin.getInitialMargin()).isEqualByComparingTo("12000");

        // Bank trading account credit-ovan za bankPart.
        ArgumentCaptor<CreditFundsRequest> creditCap = ArgumentCaptor.forClass(CreditFundsRequest.class);
        verify(bankaCoreClient).creditFunds(eq("margin-sell-200-fill-0"), creditCap.capture());
        assertThat(creditCap.getValue().amount()).isEqualByComparingTo("2000");
    }

    @Test
    void settleMarginSellFill_loanFloorZero_excessGoesToUserPart() {
        // Margin sa BP=0.5, IM=8000, MM=5000, LV=500 (mali loan).
        UserMarginAccount margin = userMargin(50L, 10L, "8000", "5000", "0.50", "500",
                "0", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));
        when(bankaCoreClient.getBankTradingAccount("RSD")).thenReturn(bankAccount(999L, "RSD"));
        when(bankaCoreClient.creditFunds(anyString(), any(CreditFundsRequest.class)))
                .thenReturn(new CreditFundsResponse(999L, new BigDecimal("105000"), new BigDecimal("1105000")));

        Order order = marginSellOrder(201L, 10L);

        BigDecimal userPart = service.settleMarginSellFill(order, 0, new BigDecimal("4000"));

        // bankPart matem = 4000 * 0.5 = 2000, ali loan je samo 500 → actualBankPart=500.
        // Visak (2000 - 500 = 1500) ide na userPart: userPart=2000+1500=3500.
        // newLoan = 0 (floor po §123).
        // newIM = 8000 + 3500 = 11500.
        assertThat(userPart).isEqualByComparingTo("3500");
        assertThat(margin.getLoanValue()).isEqualByComparingTo("0");
        assertThat(margin.getInitialMargin()).isEqualByComparingTo("11500");

        // Bank credit-ovan samo za actualBankPart=500.
        ArgumentCaptor<CreditFundsRequest> creditCap = ArgumentCaptor.forClass(CreditFundsRequest.class);
        verify(bankaCoreClient).creditFunds(eq("margin-sell-201-fill-0"), creditCap.capture());
        assertThat(creditCap.getValue().amount()).isEqualByComparingTo("500");
    }

    @Test
    void settleMarginSellFill_loanAlreadyZero_doesNotCreditBank() {
        UserMarginAccount margin = userMargin(50L, 10L, "8000", "5000", "0.50", "0",
                "0", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));

        Order order = marginSellOrder(202L, 10L);

        BigDecimal userPart = service.settleMarginSellFill(order, 0, new BigDecimal("2000"));

        // bankPart matem = 1000, ali loan je 0 → actualBankPart=0, ceo iznos ide userPart.
        assertThat(userPart).isEqualByComparingTo("2000");
        assertThat(margin.getLoanValue()).isEqualByComparingTo("0");
        assertThat(margin.getInitialMargin()).isEqualByComparingTo("10000");
        // Bank NIJE credit-ovan jer actualBankPart=0.
        verify(bankaCoreClient, never()).creditFunds(anyString(), any(CreditFundsRequest.class));
    }

    // ── Margin call check (post-mutation) ───────────────────────────────────

    @Test
    void settleMarginBuyFill_triggersMarginCallWhenIMFallsBelowMM() {
        // Margin sa IM=6000, MM=5000, reservedMargin=2500 (BP=0.5).
        // BUY fill total=4000 → userPart=2000. IM drops to 4000, below MM.
        UserMarginAccount margin = userMargin(50L, 10L, "6000", "5000", "0.50", "0",
                "2500", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));
        when(bankaCoreClient.getBankTradingAccount("RSD")).thenReturn(bankAccount(999L, "RSD"));
        when(bankaCoreClient.debitFunds(anyString(), any(DebitFundsRequest.class)))
                .thenReturn(new DebitFundsResponse(999L, new BigDecimal("100000"), new BigDecimal("900000")));

        Order order = marginBuyOrder(103L, 10L, "4000");
        service.settleMarginBuyFill(order, 0, 10, new BigDecimal("4000"));

        // userPart=2000; IM = 6000-2000 = 4000 < MM=5000 → BLOCKED + event publish.
        assertThat(margin.getInitialMargin()).isEqualByComparingTo("4000");
        assertThat(margin.getStatus()).isEqualTo(MarginAccountStatus.BLOCKED);
        verify(eventPublisher).publishEvent(any(MarginAccountBlockedEvent.class));
    }

    @Test
    void settleMarginSellFill_unblocksAccountWhenIMRisesAboveMM() {
        // P2-4 (Marzni_Racuni.txt §139): odblokiranje ide kroz UPLATU na racun, ne
        // kroz SELL fill. SELL na blokiranom racunu se NE izvrsava — testira se da
        // SELL fill na ACTIVE racunu (vec unblocked) i dalje normalno settle-uje.
        UserMarginAccount margin = userMargin(50L, 10L, "6000", "5000", "0.50", "1000",
                "0", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(eq(10L), any()))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));
        when(bankaCoreClient.getBankTradingAccount("RSD")).thenReturn(bankAccount(999L, "RSD"));
        when(bankaCoreClient.creditFunds(anyString(), any(CreditFundsRequest.class)))
                .thenReturn(new CreditFundsResponse(999L, new BigDecimal("105000"), new BigDecimal("1105000")));

        Order order = marginSellOrder(203L, 10L);

        // SELL fill total=6000. bankPart=3000 → loan ide na 0, actualBankPart=1000, visak=2000 ide u userPart.
        // userPart = 3000 + 2000 = 5000. IM = 6000 + 5000 = 11000.
        service.settleMarginSellFill(order, 0, new BigDecimal("6000"));

        assertThat(margin.getInitialMargin()).isEqualByComparingTo("11000");
        assertThat(margin.getStatus()).isEqualTo(MarginAccountStatus.ACTIVE);
    }

    // ── P2-4: SELL fill na BLOCKED racunu odbijen po §137 ───────────────────

    @Test
    void settleMarginSellFill_throwsWhenAccountBlocked() {
        // Marzni_Racuni.txt §137: blokiran margin racun odbija SVAKI buy/sell pokusaj.
        UserMarginAccount margin = userMargin(50L, 10L, "10000", "5000", "0.50", "3000",
                "0", MarginAccountStatus.BLOCKED);
        when(marginAccountRepository.findFirstByUserIdAndStatus(eq(10L), any()))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));

        Order order = marginSellOrder(204L, 10L);

        assertThatThrownBy(() -> service.settleMarginSellFill(order, 0, new BigDecimal("4000")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BLOCKED");

        // Nikakva mutacija salda / banka-core operacija na blokiranom racunu.
        verify(bankaCoreClient, never()).creditFunds(anyString(), any(CreditFundsRequest.class));
        assertThat(margin.getLoanValue()).isEqualByComparingTo("3000");
        assertThat(margin.getInitialMargin()).isEqualByComparingTo("10000");
    }

    // ── P2-4: read-only blocked detekcija za executor decline putanju ────────

    @Test
    void isMarginAccountBlocked_returnsTrueWhenBlocked() {
        UserMarginAccount margin = userMargin(50L, 10L, "1000", "5000", "0.50", "0",
                "0", MarginAccountStatus.BLOCKED);
        // ACTIVE lookup prazan (racun je blokiran), fallback na findByUserId.
        when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(marginAccountRepository.findByUserId(10L)).thenReturn(java.util.List.of(margin));

        Order order = marginSellOrder(205L, 10L);
        assertThat(service.isMarginAccountBlocked(order)).isTrue();
    }

    @Test
    void isMarginAccountBlocked_returnsFalseWhenActive() {
        UserMarginAccount margin = userMargin(50L, 10L, "10000", "5000", "0.50", "0",
                "0", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(margin));

        Order order = marginSellOrder(206L, 10L);
        assertThat(service.isMarginAccountBlocked(order)).isFalse();
    }

    @Test
    void isMarginAccountBlocked_returnsFalseForNonMarginOrder() {
        Order order = marginSellOrder(207L, 10L);
        order.setMargin(false);
        assertThat(service.isMarginAccountBlocked(order)).isFalse();
        verify(marginAccountRepository, never()).findFirstByUserIdAndStatus(any(), any());
    }

    // ── Reservation release (cancel/decline) ────────────────────────────────

    @Test
    void releaseMarginBuyReservation_rollsBackReservedMargin() {
        UserMarginAccount margin = userMargin(50L, 10L, "10000", "5000", "0.50", "0",
                "2500", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));

        Order order = marginBuyOrder(104L, 10L, "5000");
        service.releaseMarginBuyReservation(order, new BigDecimal("5000"));

        // userPart = 2500. reservedMargin: 2500 - 2500 = 0.
        assertThat(margin.getReservedMargin()).isEqualByComparingTo("0");
    }

    @Test
    void settleMarginBuyFill_throwsWhenAccountBlocked() {
        UserMarginAccount margin = userMargin(50L, 10L, "10000", "5000", "0.50", "0",
                "0", MarginAccountStatus.BLOCKED);
        when(marginAccountRepository.findFirstByUserIdAndStatus(eq(10L), any()))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));

        Order order = marginBuyOrder(105L, 10L, "1000");

        assertThatThrownBy(() -> service.settleMarginBuyFill(order, 0, 10, new BigDecimal("1000")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BLOCKED");
    }

    // ── P1-margin-1 (R2 1325): visak rezervacije pri fill-u po nizoj ceni ───

    @Test
    void settleMarginBuyFill_fillBelowApprox_releasesSurplusReservation() {
        // Rezervacija je napravljena po APPROX ceni 5000 (userPart=2500), a fill
        // ide po STVARNOJ nizoj ceni 4000 (userPart=2000). Visak 500 mora da se
        // oslobodi iz reservedMargin (inace ostaje zaglavljen → availableIM trajno
        // umanjen). reservedMargin pocetni = 2500 (za ceo nalog).
        UserMarginAccount margin = userMargin(50L, 10L, "20000", "5000", "0.50", "0",
                "2500", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));
        when(bankaCoreClient.getBankTradingAccount("RSD")).thenReturn(bankAccount(999L, "RSD"));
        when(bankaCoreClient.debitFunds(anyString(), any(DebitFundsRequest.class)))
                .thenReturn(new DebitFundsResponse(999L, new BigDecimal("100000"), new BigDecimal("900000")));

        // approx=5000 za ceo nalog (10 kom), exchangeRate=1; fill ceo nalog (10 kom)
        // ali po nizem total 4000.
        Order order = marginBuyOrder(110L, 10L, "5000");
        order.setExchangeRate(BigDecimal.ONE);

        service.settleMarginBuyFill(order, 0, 10, new BigDecimal("4000"));

        // userPart(actual)=2000 skinut sa IM; reservedUserPart(approx)=2500.
        // Visak = 2500 - 2000 = 500. reservedMargin: 2500 - 2000(actual) - 500(visak) = 0.
        assertThat(margin.getReservedMargin()).isEqualByComparingTo("0");
        // IM skinut samo za stvarno utroseni userPart 2000.
        assertThat(margin.getInitialMargin()).isEqualByComparingTo("18000");
    }

    @Test
    void settleMarginBuyFill_partialFillBelowApprox_releasesProRataSurplus() {
        // approx=5000 (10 kom). Parcijalni fill 5 kom po total 1800 (niza cena).
        // reservedUserPart(fill 5 kom) = 5000*0.5*5/10 = 1250.
        // actualUserPart = 1800*0.5 = 900. Visak = 1250 - 900 = 350.
        // reservedMargin: 2500 - 900(actual) - 350(visak) = 1250 (ostaje za preostalih 5 kom).
        UserMarginAccount margin = userMargin(50L, 10L, "20000", "5000", "0.50", "0",
                "2500", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));
        when(bankaCoreClient.getBankTradingAccount("RSD")).thenReturn(bankAccount(999L, "RSD"));
        when(bankaCoreClient.debitFunds(anyString(), any(DebitFundsRequest.class)))
                .thenReturn(new DebitFundsResponse(999L, new BigDecimal("100000"), new BigDecimal("900000")));

        Order order = marginBuyOrder(111L, 10L, "5000");
        order.setExchangeRate(BigDecimal.ONE);

        service.settleMarginBuyFill(order, 0, 5, new BigDecimal("1800"));

        assertThat(margin.getReservedMargin()).isEqualByComparingTo("1250");
        assertThat(margin.getInitialMargin()).isEqualByComparingTo("19100"); // 20000 - 900
    }

    @Test
    void settleMarginBuyFill_fillAtApprox_noSurplusReleased() {
        // Regression: fill cena == approx → nema viska, ponasa se kao pre fix-a.
        UserMarginAccount margin = userMargin(50L, 10L, "20000", "5000", "0.50", "0",
                "2500", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));
        when(bankaCoreClient.getBankTradingAccount("RSD")).thenReturn(bankAccount(999L, "RSD"));
        when(bankaCoreClient.debitFunds(anyString(), any(DebitFundsRequest.class)))
                .thenReturn(new DebitFundsResponse(999L, new BigDecimal("100000"), new BigDecimal("900000")));

        Order order = marginBuyOrder(112L, 10L, "5000");
        order.setExchangeRate(BigDecimal.ONE);

        service.settleMarginBuyFill(order, 0, 10, new BigDecimal("5000"));

        // userPart=2500 == reservedUserPart → reservedMargin 2500 - 2500 = 0, bez viska.
        assertThat(margin.getReservedMargin()).isEqualByComparingTo("0");
        assertThat(margin.getInitialMargin()).isEqualByComparingTo("17500");
    }

    // ── P1-margin-1 (R3 1548): post-fill margin call koristi RASPOLOZIVI IM ──

    @Test
    void settleMarginBuyFill_marginCallUsesAvailableImNotRawIm() {
        // Posle fill-a: IM=8000 (sirov), reservedMargin=4000 (drugi in-flight BUY),
        // MM=5000. Sirov IM (8000) >= MM → stari kod NE bi blokirao. Raspolozivi IM
        // (8000 - 4000 = 4000) < MM=5000 → mora se BLOKIRATI (kasni margin call fix).
        // Krecemo od IM=10000, reservedMargin=4500 (4000 ostatak + 500 za ovaj fill).
        // BUY fill total=1000 → userPart=500. IM 10000-500=9500? Ne — namestamo da
        // posle fill-a IM=8000: krecemo IM=8500, fill userPart=500 → IM=8000.
        UserMarginAccount margin = userMargin(50L, 10L, "8500", "5000", "0.50", "0",
                "4500", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));
        when(bankaCoreClient.getBankTradingAccount("RSD")).thenReturn(bankAccount(999L, "RSD"));
        when(bankaCoreClient.debitFunds(anyString(), any(DebitFundsRequest.class)))
                .thenReturn(new DebitFundsResponse(999L, new BigDecimal("100000"), new BigDecimal("900000")));
        when(bankaCoreClient.getUserById("CLIENT", 10L)).thenReturn(
                new InternalUserDto(10L, "CLIENT", "owner@test.com", "Owner", "Test", true, null));

        // approx=1000 (10 kom), fill ceo (10 kom) po total 1000 → userPart=500, bez viska.
        Order order = marginBuyOrder(120L, 10L, "1000");
        order.setExchangeRate(BigDecimal.ONE);

        service.settleMarginBuyFill(order, 0, 10, new BigDecimal("1000"));

        // IM (sirov) = 8500 - 500 = 8000 (>= MM); reservedMargin = 4500 - 500 = 4000.
        // availableIM = 8000 - 4000 = 4000 < MM=5000 → BLOCKED.
        assertThat(margin.getInitialMargin()).isEqualByComparingTo("8000");
        assertThat(margin.getReservedMargin()).isEqualByComparingTo("4000");
        assertThat(margin.getStatus()).isEqualTo(MarginAccountStatus.BLOCKED);
        verify(eventPublisher).publishEvent(any(MarginAccountBlockedEvent.class));
    }

    @Test
    void settleMarginBuyFill_marginCall_resolvesOwnerEmailNotNull() {
        // P1-margin-1 (R1 204): post-fill margin call mora razresiti email vlasnika
        // (ranije slao null → notifikacija tiho preskocena).
        UserMarginAccount margin = userMargin(50L, 10L, "6000", "5000", "0.50", "0",
                "0", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(margin));
        when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));
        when(bankaCoreClient.getBankTradingAccount("RSD")).thenReturn(bankAccount(999L, "RSD"));
        when(bankaCoreClient.debitFunds(anyString(), any(DebitFundsRequest.class)))
                .thenReturn(new DebitFundsResponse(999L, new BigDecimal("100000"), new BigDecimal("900000")));
        when(bankaCoreClient.getUserById("CLIENT", 10L)).thenReturn(
                new InternalUserDto(10L, "CLIENT", "client@test.com", "Cli", "Ent", true, null));

        Order order = marginBuyOrder(121L, 10L, "4000");
        order.setExchangeRate(BigDecimal.ONE);

        // fill total=4000 → userPart=2000. IM=6000-2000=4000 < MM=5000 → BLOCKED.
        service.settleMarginBuyFill(order, 0, 10, new BigDecimal("4000"));

        ArgumentCaptor<MarginAccountBlockedEvent> cap =
                ArgumentCaptor.forClass(MarginAccountBlockedEvent.class);
        verify(eventPublisher).publishEvent(cap.capture());
        assertThat(cap.getValue().getEmail()).isEqualTo("client@test.com");
    }

    // ── OT-1106: E2E margin order round-trip (reserve → BUY fill → SELL fill) ─

    @Nested
    @DisplayName("OT-1106: E2E margin BUY/SELL split round-trip preko settlement-a")
    class MarginRoundTripE2E {

        @Test
        @DisplayName("reserve → BUY fill → SELL fill nad ISTIM racunom: loan otvoren BUY-em vraca se "
                + "SELL-om; IM se vraca; bank debit (BUY) == bank credit (SELL) za loan deo")
        void reserveBuyFill_thenSellFill_conservesBankAndUserParts() {
            // Pun zivotni ciklus margin pozicije kroz order engine settlement seam:
            // 1) reserveForMarginBuy izrezuje userPart hold iz IM-a,
            // 2) settleMarginBuyFill troshi hold, povecava loan, debituje bankin racun,
            // 3) settleMarginSellFill vraca loan, kredituje bankin racun, vraca userPart u IM.
            // Postojeci testovi proveravaju svaki korak ZASEBNO (izolovan margin state);
            // ovaj pinuje da BUY i SELL leg-ovi KONZERVIRAJU kad se izvrse u nizu nad
            // istim entitetom (loan koji BUY otvori, SELL zatvori; bank-deo se vrati).
            UserMarginAccount margin = userMargin(50L, 10L, "20000", "5000", "0.50", "0",
                    "0", MarginAccountStatus.ACTIVE);
            when(marginAccountRepository.findFirstByUserIdAndStatus(10L, MarginAccountStatus.ACTIVE))
                    .thenReturn(Optional.of(margin));
            when(marginAccountRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(margin));
            when(bankaCoreClient.getBankTradingAccount("RSD")).thenReturn(bankAccount(999L, "RSD"));
            when(bankaCoreClient.debitFunds(anyString(), any(DebitFundsRequest.class)))
                    .thenReturn(new DebitFundsResponse(999L, new BigDecimal("100000"), new BigDecimal("900000")));
            when(bankaCoreClient.creditFunds(anyString(), any(CreditFundsRequest.class)))
                    .thenReturn(new CreditFundsResponse(999L, new BigDecimal("105000"), new BigDecimal("1105000")));

            BigDecimal startIm = margin.getInitialMargin();

            // 1) Rezervacija za BUY total=5000 → userPart=2500 hold.
            Order buy = marginBuyOrder(300L, 10L, "5000");
            buy.setExchangeRate(BigDecimal.ONE);
            boolean reserved = service.reserveForMarginBuy(buy, new BigDecimal("5000"));
            assertThat(reserved).isTrue();
            assertThat(margin.getReservedMargin()).isEqualByComparingTo("2500");

            // 2) BUY fill po istom total=5000 (full): bankPart=2500 debituje banku,
            //    userPart=2500 troshi hold → loan=2500, IM=17500, reserved=0.
            service.settleMarginBuyFill(buy, 0, 10, new BigDecimal("5000"));
            assertThat(margin.getReservedMargin()).isEqualByComparingTo("0");
            assertThat(margin.getInitialMargin()).isEqualByComparingTo("17500");
            assertThat(margin.getLoanValue()).isEqualByComparingTo("2500");

            ArgumentCaptor<DebitFundsRequest> debitCap = ArgumentCaptor.forClass(DebitFundsRequest.class);
            verify(bankaCoreClient).debitFunds(eq("margin-buy-300-fill-0"), debitCap.capture());
            BigDecimal bankBuyLeg = debitCap.getValue().amount();
            assertThat(bankBuyLeg).isEqualByComparingTo("2500");

            // 3) SELL fill po total=5000 (zatvara poziciju): bankPart=2500, loan=2500 →
            //    actualBankPart=2500 (ceo loan se vrati, floor 0), userPart=2500 → IM nazad na 20000.
            Order sell = marginSellOrder(301L, 10L);
            BigDecimal userPart = service.settleMarginSellFill(sell, 0, new BigDecimal("5000"));

            assertThat(margin.getLoanValue()).isEqualByComparingTo("0");          // loan vracen
            assertThat(margin.getInitialMargin()).isEqualByComparingTo(startIm);  // IM vracen na pocetak
            assertThat(margin.getReservedMargin()).isEqualByComparingTo("0");
            assertThat(userPart).isEqualByComparingTo("2500");

            // Bank credit (SELL loan-repay) == bank debit (BUY loan-fund) → bankin novac konzerviran.
            ArgumentCaptor<CreditFundsRequest> creditCap = ArgumentCaptor.forClass(CreditFundsRequest.class);
            verify(bankaCoreClient).creditFunds(eq("margin-sell-301-fill-0"), creditCap.capture());
            assertThat(creditCap.getValue().amount()).isEqualByComparingTo(bankBuyLeg);

            // Tacno jedan bank debit i jedan bank credit u celom round-trip-u.
            verify(bankaCoreClient, times(1)).debitFunds(anyString(), any(DebitFundsRequest.class));
            verify(bankaCoreClient, times(1)).creditFunds(anyString(), any(CreditFundsRequest.class));
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private UserMarginAccount userMargin(Long id, Long userId, String im, String mm, String bp,
                                          String loan, String reserved, MarginAccountStatus status) {
        UserMarginAccount m = UserMarginAccount.builder()
                .id(id)
                .accountId(500L)
                .accountNumber("222000112345678911")
                .userId(userId)
                .currency("RSD")
                .initialMargin(new BigDecimal(im))
                .maintenanceMargin(new BigDecimal(mm))
                .bankParticipation(new BigDecimal(bp))
                .loanValue(new BigDecimal(loan))
                .reservedMargin(new BigDecimal(reserved))
                .status(status)
                .build();
        return m;
    }

    private InternalAccountDto bankAccount(Long id, String currency) {
        return new InternalAccountDto(id, "111-bank", "Banka 2",
                new BigDecimal("1000000"), new BigDecimal("1000000"), BigDecimal.ZERO,
                currency, "ACTIVE", null, null, "BANK_TRADING");
    }

    private Order marginBuyOrder(Long orderId, Long userId, String approxPrice) {
        Order o = new Order();
        o.setId(orderId);
        o.setUserId(userId);
        o.setUserRole("CLIENT");
        o.setQuantity(10);
        o.setRemainingPortions(10);
        o.setDirection(OrderDirection.BUY);
        o.setMargin(true);
        o.setApproximatePrice(new BigDecimal(approxPrice));
        o.setReservationReleased(false);
        return o;
    }

    private Order marginSellOrder(Long orderId, Long userId) {
        Order o = new Order();
        o.setId(orderId);
        o.setUserId(userId);
        o.setUserRole("CLIENT");
        o.setQuantity(10);
        o.setRemainingPortions(10);
        o.setDirection(OrderDirection.SELL);
        o.setMargin(true);
        o.setReservationReleased(false);
        return o;
    }
}
