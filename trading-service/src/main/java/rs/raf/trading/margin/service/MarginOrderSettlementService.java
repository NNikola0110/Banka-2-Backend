package rs.raf.trading.margin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.DebitFundsRequest;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.margin.event.MarginAccountBlockedEvent;
import rs.raf.trading.margin.model.MarginAccount;
import rs.raf.trading.margin.model.MarginAccountStatus;
import rs.raf.trading.margin.model.MarginCallPolicy;
import rs.raf.trading.margin.model.MarginTransaction;
import rs.raf.trading.margin.model.MarginTransactionType;
import rs.raf.trading.margin.repository.MarginAccountRepository;
import rs.raf.trading.margin.repository.MarginTransactionRepository;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.model.Order;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * BE-STK-05 (25.05.2026): orchestrator za margin BUY/SELL split logiku.
 *
 * <p>Po Marzni_Racuni.txt §75-123: kada se nalog izvrsi na margin racunu,
 * total se splituje na dva dela:
 * <ul>
 *   <li><b>BUY</b>: total = qty × pricePerShare
 *     <ul>
 *       <li>bankPart = total × bankParticipation → dodaje se na LoanValue +
 *           debit-uje bankin trading racun za bankPart</li>
 *       <li>userPart = total - bankPart → debit-uje se MarginAccount.initialMargin</li>
 *       <li>Ako reservedMargin nije dovoljan → REJECT ceo BUY pre execute</li>
 *     </ul>
 *   </li>
 *   <li><b>SELL</b>: total = qty × marketBidPrice
 *     <ul>
 *       <li>bankPart = total × bankParticipation → subtract iz LoanValue (floor 0)
 *           + credit bankin trading racun</li>
 *       <li>userPart = total - bankPart → add na MarginAccount.initialMargin</li>
 *       <li>Trigger checkMaintenanceMargin posle mutacije (publish MarginAccountBlockedEvent
 *           ako padne ispod MM)</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Money operacije idu kroz {@link BankaCoreClient#debitFunds}/
 * {@link BankaCoreClient#creditFunds} (banka-core ima bank trading account).
 * Lokalna {@link MarginAccount} mutacija ide kroz {@code findByIdForUpdate}
 * pessimistic lock + {@code @Version} optimistic check za concurrent BUY/SELL race.
 *
 * <p>Idempotency keys (deterministicki za retry replay): {@code margin-buy-{orderId}-fill-{seq}}
 * / {@code margin-sell-{orderId}-fill-{seq}} — paritet sa
 * {@code FundReservationService.consumeForBuyFill} obrazcem.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarginOrderSettlementService {

    private final MarginAccountRepository marginAccountRepository;
    private final MarginTransactionRepository marginTransactionRepository;
    private final BankaCoreClient bankaCoreClient;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * BE-STK-05: rezervise userPart sa initialMargin pri APPROVED BUY (pre fill engine).
     *
     * <p>Po Marzni_Racuni.txt §101: "Ukoliko nemamo dovoljno sredstava na InitialMargin,
     * cela transakcija se ponistava!" — ovo se proverava na ovom mestu (pre fill engine
     * uopste pocne).
     *
     * @return true ako je rezervacija uspesna; false ako je insufficient.
     */
    @Transactional
    public boolean reserveForMarginBuy(Order order, BigDecimal totalAmount) {
        MarginAccount margin = lockMarginAccount(order);
        if (margin == null) {
            throw new IllegalStateException(
                    "Margin order " + order.getId() + " nema povezan margin racun.");
        }
        ensureActive(margin);

        BigDecimal bankPart = totalAmount.multiply(margin.getBankParticipation())
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal userPart = totalAmount.subtract(bankPart).setScale(4, RoundingMode.HALF_UP);

        BigDecimal available = margin.getAvailableInitialMargin();
        if (available.compareTo(userPart) < 0) {
            log.warn("Margin BUY order #{} insufficient: userPart={} > availableMargin={}",
                    order.getId(), userPart, available);
            return false;
        }

        BigDecimal rm = margin.getReservedMargin() != null ? margin.getReservedMargin() : BigDecimal.ZERO;
        margin.setReservedMargin(rm.add(userPart).setScale(4, RoundingMode.HALF_UP));
        marginAccountRepository.save(margin);

        log.info("Margin BUY order #{} reserved userPart={} on margin#{} (bankPart={} pending fill)",
                order.getId(), userPart, margin.getId(), bankPart);
        return true;
    }

    /**
     * BE-STK-05: rollback rezervacije za margin BUY (cancel / decline / failed fill).
     */
    @Transactional
    public void releaseMarginBuyReservation(Order order, BigDecimal totalAmount) {
        MarginAccount margin = lockMarginAccount(order);
        if (margin == null) {
            return;
        }
        BigDecimal bankPart = totalAmount.multiply(margin.getBankParticipation())
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal userPart = totalAmount.subtract(bankPart).setScale(4, RoundingMode.HALF_UP);

        BigDecimal rm = margin.getReservedMargin() != null ? margin.getReservedMargin() : BigDecimal.ZERO;
        BigDecimal newReserved = rm.subtract(userPart).max(BigDecimal.ZERO)
                .setScale(4, RoundingMode.HALF_UP);
        margin.setReservedMargin(newReserved);
        marginAccountRepository.save(margin);

        log.info("Margin BUY order #{} released reservation userPart={} on margin#{}",
                order.getId(), userPart, margin.getId());
    }

    /**
     * BE-STK-05: knjizi jedan partial fill za margin BUY.
     *
     * <p>Marzni_Racuni.txt §89-101:
     * <ul>
     *   <li>bankPart → dodaje na LoanValue + debit bankin trading racun (banka-core)</li>
     *   <li>userPart → skida se sa initialMargin + reservedMargin (rollback rezervacije
     *       koja je vec napravljena u reserveForMarginBuy)</li>
     * </ul>
     *
     * @param fillSeq redni broj fill-a (za idempotency key)
     * @param fillQuantity kolicina ovog fill-a (za pro-rata oslobadjanje viska rezervacije)
     * @param fillTotal total = qty × pricePerShare za ovaj fill
     */
    @Transactional
    public void settleMarginBuyFill(Order order, int fillSeq, int fillQuantity, BigDecimal fillTotal) {
        MarginAccount margin = lockMarginAccount(order);
        if (margin == null) {
            throw new IllegalStateException(
                    "Margin order " + order.getId() + " nema povezan margin racun.");
        }
        ensureActive(margin);

        BigDecimal bankPart = fillTotal.multiply(margin.getBankParticipation())
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal userPart = fillTotal.subtract(bankPart).setScale(4, RoundingMode.HALF_UP);

        // P1-margin-1 (R2 1325) — visak rezervacije pri fill-u po nizoj ceni.
        // Rezervacija (reserveForMarginBuy) je napravljena po APPROX ceni naloga, a
        // fill ide po STVARNOJ trzisnoj ceni. Ako je fill cena < approx, userPart
        // ovog fill-a (skinut sa reservedMargin) je manji od pro-rata REZERVISANOG
        // userPart-a za ovu kolicinu → razlika ostaje zaglavljena u reservedMargin.
        // Na DONE {@code releaseReservationSafe} racuna ostatak preko
        // {@code remainingPortions} (=0) → 0 → visak NIKAD ne oslobodi
        // (availableInitialMargin trajno umanjen). Resenje: za svaki fill oslobodi
        // PRO-RATA visak rezervacije {@code reservedUserPart(fill) − userPart(fill)}.
        BigDecimal reservedReleaseSurplus = computeReservationSurplusForFill(
                order, margin, fillQuantity, userPart);

        // 1. Skini userPart sa initialMargin + reservedMargin (rezervacija konzumirana),
        //    plus oslobodi pro-rata visak rezervacije (fill cena < approx).
        BigDecimal newIm = margin.getInitialMargin().subtract(userPart)
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal rm = margin.getReservedMargin() != null ? margin.getReservedMargin() : BigDecimal.ZERO;
        BigDecimal newReserved = rm.subtract(userPart).subtract(reservedReleaseSurplus).max(BigDecimal.ZERO)
                .setScale(4, RoundingMode.HALF_UP);

        if (newIm.compareTo(BigDecimal.ZERO) < 0) {
            // Defenzivni guard — reservacija je trebalo da spreci ovo.
            throw new InsufficientFundsException(
                    "Margin BUY order " + order.getId() + " fill " + fillSeq
                            + " bi dovelo initialMargin u minus: " + newIm);
        }

        margin.setInitialMargin(newIm);
        margin.setReservedMargin(newReserved);

        // 2. Dodaj bankPart na LoanValue (banka pozajmljuje korisniku).
        BigDecimal newLoan = margin.getLoanValue().add(bankPart).setScale(4, RoundingMode.HALF_UP);
        margin.setLoanValue(newLoan);

        marginAccountRepository.save(margin);

        // 3. Debit bankin trading racun za bankPart (banka prebacuje pare ka berzi).
        // Marzni_Racuni.txt §95: "Tu inicirati transakciju od racuna banke ka racunu berze".
        // Banka-core debituje BANK_TRADING racun u valuti margin racuna (RSD).
        try {
            String bankAccountCurrency = margin.getCurrency() != null ? margin.getCurrency() : "RSD";
            bankaCoreClient.debitFunds(
                    "margin-buy-" + order.getId() + "-fill-" + fillSeq,
                    new DebitFundsRequest(
                            resolveBankTradingAccountId(bankAccountCurrency),
                            bankPart,
                            BigDecimal.ZERO,
                            bankAccountCurrency,
                            "Margin BUY order #" + order.getId() + " fill " + fillSeq
                                    + " (bankPart, loan += " + bankPart + ")"));
        } catch (BankaCoreClientException ex) {
            // Banka-core nije uspeo da debituje — propagiraj da @Transactional uradi rollback.
            log.error("Margin BUY fill #{}/{} bank debit failed: {}", order.getId(), fillSeq, ex.getMessage());
            throw ex;
        }

        // 4. Audit transakcija
        marginTransactionRepository.save(MarginTransaction.builder()
                .marginAccount(margin)
                .type(MarginTransactionType.BUY)
                .amount(fillTotal)
                .description("Margin BUY fill " + fillSeq + " order#" + order.getId()
                        + " (userPart=" + userPart + ", bankPart=" + bankPart + ")")
                .build());

        log.info("Margin BUY order #{} fill {} settled: IM {} → {}, Loan {} → {}",
                order.getId(), fillSeq,
                margin.getInitialMargin().add(userPart), newIm,
                margin.getLoanValue().subtract(bankPart), newLoan);

        // 5. Trigger margin call check posle mutacije (BUY moze da gurne IM ispod MM
        // ako su komisije/FX dodatni troskovi, ali u ovoj implementaciji BUY samo skida
        // userPart, pa MM check je defenzivan).
        checkMarginCallAndBlock(margin);
    }

    /**
     * BE-STK-05: knjizi jedan partial fill za margin SELL.
     *
     * <p>Marzni_Racuni.txt §103-123:
     * <ul>
     *   <li>bankPart → subtract iz LoanValue (floor 0 po §123: "Ako loanValue padne na 0,
     *       ne placamo banci vise nista") + credit bankin trading racun</li>
     *   <li>userPart → add na MarginAccount.initialMargin</li>
     * </ul>
     *
     * @return BigDecimal userPart koji je dodato na IM (caller moze logovati).
     */
    @Transactional
    public BigDecimal settleMarginSellFill(Order order, int fillSeq, BigDecimal fillTotal) {
        MarginAccount margin = lockMarginAccount(order);
        if (margin == null) {
            throw new IllegalStateException(
                    "Margin order " + order.getId() + " nema povezan margin racun.");
        }
        // P2-4 (Marzni_Racuni.txt §137): blokiran margin racun odbija SVAKI pokusaj
        // kupovine/prodaje — ne samo BUY. Raniji komentar je tvrdio da je SELL
        // dozvoljen na blokiranom racunu ("recover via SELL"), ali to NIJE u skladu
        // sa literalnim spec-om §137 koji glasi: "svaki pokusaj kupovine/prodaje
        // Stocks preko marznog racuna automatski odbija". Odblokiranje ide
        // iskljucivo kroz uplatu na racun (Marzni_Racuni.txt §139), ne kroz SELL fill.
        // OrderExecutionService/SingleOrderExecutor pre-checkuje blokiran status i
        // cisto declime order (bez beskonacnog retry-a); ovaj guard je hard
        // enforcement po spec-u za slucaj direktnog poziva.
        ensureActive(margin);

        BigDecimal bankPart = fillTotal.multiply(margin.getBankParticipation())
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal userPart = fillTotal.subtract(bankPart).setScale(4, RoundingMode.HALF_UP);

        // 1. Subtract bankPart iz LoanValue (floor 0).
        BigDecimal currentLoan = margin.getLoanValue();
        BigDecimal newLoan;
        BigDecimal actualBankPart;
        if (currentLoan.compareTo(bankPart) >= 0) {
            newLoan = currentLoan.subtract(bankPart).setScale(4, RoundingMode.HALF_UP);
            actualBankPart = bankPart;
        } else {
            // Loan ce padne na 0 — visak ide na userPart (Marzni_Racuni.txt §123).
            actualBankPart = currentLoan;
            BigDecimal excessToUser = bankPart.subtract(actualBankPart);
            userPart = userPart.add(excessToUser).setScale(4, RoundingMode.HALF_UP);
            newLoan = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        margin.setLoanValue(newLoan);

        // 2. Add userPart na initialMargin.
        BigDecimal newIm = margin.getInitialMargin().add(userPart).setScale(4, RoundingMode.HALF_UP);
        margin.setInitialMargin(newIm);

        marginAccountRepository.save(margin);

        // 3. Credit bankin trading racun za actualBankPart (vraca pozajmicu).
        if (actualBankPart.signum() > 0) {
            try {
                String bankAccountCurrency = margin.getCurrency() != null ? margin.getCurrency() : "RSD";
                bankaCoreClient.creditFunds(
                        "margin-sell-" + order.getId() + "-fill-" + fillSeq,
                        new CreditFundsRequest(
                                resolveBankTradingAccountId(bankAccountCurrency),
                                actualBankPart,
                                BigDecimal.ZERO,
                                bankAccountCurrency,
                                "Margin SELL order #" + order.getId() + " fill " + fillSeq
                                        + " (bankPart repayment, loan -= " + actualBankPart + ")"));
            } catch (BankaCoreClientException ex) {
                log.error("Margin SELL fill #{}/{} bank credit failed: {}",
                        order.getId(), fillSeq, ex.getMessage());
                throw ex;
            }
        }

        // 4. Audit transakcija
        marginTransactionRepository.save(MarginTransaction.builder()
                .marginAccount(margin)
                .type(MarginTransactionType.SELL)
                .amount(fillTotal)
                .description("Margin SELL fill " + fillSeq + " order#" + order.getId()
                        + " (userPart=" + userPart + ", bankPart=" + actualBankPart + ")")
                .build());

        log.info("Margin SELL order #{} fill {} settled: IM {} → {}, Loan {} → {}",
                order.getId(), fillSeq,
                margin.getInitialMargin().subtract(userPart), newIm,
                currentLoan, newLoan);

        // 5. Trigger margin call check posle SELL (mozda je dodato dovoljno da odblokira).
        checkMarginCallAndBlock(margin);

        return userPart;
    }

    /**
     * P1-margin-1 (R2 1325): pro-rata visak rezervacije za jedan margin BUY fill.
     *
     * <p>Rezervisani userPart za CEO nalog (reserveForMarginBuy) je baziran na
     * APPROX iznosu naloga u valuti margin racuna (RSD):
     * {@code reservedTotalRsd = approximatePrice × exchangeRate} (gde je
     * exchangeRate listing→RSD, postavljen za margin ordere u OrderServiceImpl).
     * userPart faktor = {@code 1 − bankParticipation}. Pro-rata po kolicini fill-a:
     * {@code reservedUserPart(fill) = reservedTotalRsd × (1 − BP) × fillQty / totalQty}.
     *
     * <p>Visak koji treba osloboditi = {@code max(0, reservedUserPart(fill) −
     * actualUserPart(fill))}. actualUserPart je vec skinut sa reservedMargin u
     * settle-u; ovaj visak je deo koji je bio rezervisan ali ne i utrosen (fill po
     * nizoj ceni od approx). Ako fill cena >= approx (visak <= 0), vraca 0 — nista
     * dodatno se ne oslobadja (settle skida samo stvarno utroseni userPart).
     *
     * <p>Defenzivno vraca 0 ako order nema {@code approximatePrice}/{@code quantity}
     * ({@code exchangeRate} null → 1) — legacy/ne-margin slucaj.
     */
    private BigDecimal computeReservationSurplusForFill(Order order, MarginAccount margin,
                                                        int fillQuantity, BigDecimal actualUserPart) {
        if (order.getApproximatePrice() == null || order.getQuantity() == null
                || order.getQuantity() <= 0 || fillQuantity <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = order.getExchangeRate() != null ? order.getExchangeRate() : BigDecimal.ONE;
        BigDecimal reservedTotalInMarginCcy = order.getApproximatePrice().multiply(rate);
        BigDecimal userFactor = BigDecimal.ONE.subtract(margin.getBankParticipation());
        BigDecimal reservedUserPartForFill = reservedTotalInMarginCcy
                .multiply(userFactor)
                .multiply(BigDecimal.valueOf(fillQuantity))
                .divide(BigDecimal.valueOf(order.getQuantity()), 4, RoundingMode.HALF_UP);
        return reservedUserPartForFill.subtract(actualUserPart).max(BigDecimal.ZERO)
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Resolves margin account za order. Vraca null ako nije margin order.
     */
    private MarginAccount lockMarginAccount(Order order) {
        if (order == null || !order.isMargin()) {
            return null;
        }
        // BUY: rezervisani racun je margin account; SELL: order.reservedAccountId
        // je receiving account ali za margin SELL je takodje margin account.
        // Za margin order, banka-core racun id NIJE relevantan — koristimo userId
        // + status ACTIVE da nadjemo margin racun (BE-STK-05 invariantno: jedan margin
        // racun po klijentu).
        Long ownerId = order.getUserId();
        // Pessimistic lock na margin racunu klijenta. Marzni_Racuni.txt §57:
        // "korisnik moze imati samo jedan marzni racun".
        return marginAccountRepository.findFirstByUserIdAndStatus(ownerId, MarginAccountStatus.ACTIVE)
                .or(() -> marginAccountRepository.findByUserId(ownerId).stream().findFirst())
                .flatMap(m -> marginAccountRepository.findByIdForUpdate(m.getId()))
                .orElse(null);
    }

    private void ensureActive(MarginAccount margin) {
        if (margin.getStatus() != MarginAccountStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Margin racun #" + margin.getId() + " je BLOCKED — trgovina nije dozvoljena.");
        }
    }

    /**
     * P2-4: read-only provera da li je margin racun ovog ordera BLOCKED.
     *
     * <p>Koristi je {@code SingleOrderExecutor} da PRE fill-a odluci da li margin
     * BUY/SELL fill treba odbiti (Marzni_Racuni.txt §137) — i to tako sto cisto
     * declime order, umesto da pusti {@link #settleMarginSellFill}/
     * {@link #settleMarginBuyFill} da baci {@code IllegalStateException} koji bi
     * fill engine retry-ovao svaki tick (beskonacno).
     *
     * <p>Bez pessimistic lock-a (samo citanje statusa). Vraca {@code false} ako
     * order nije margin ili racun nije pronadjen (tada regularna fill putanja
     * preuzima i baca svoju gresku).
     *
     * @return {@code true} ako je margin racun pronadjen i status mu je BLOCKED.
     */
    @Transactional(readOnly = true)
    public boolean isMarginAccountBlocked(Order order) {
        if (order == null || !order.isMargin()) {
            return false;
        }
        Long ownerId = order.getUserId();
        return marginAccountRepository.findFirstByUserIdAndStatus(ownerId, MarginAccountStatus.ACTIVE)
                .or(() -> marginAccountRepository.findByUserId(ownerId).stream().findFirst())
                .map(m -> m.getStatus() == MarginAccountStatus.BLOCKED)
                .orElse(false);
    }

    /**
     * Marzni_Racuni.txt §133-139: ako RASPOLOZIVI IM padne ispod MM, racun se
     * blokira. Ako predje MM (npr. posle SELL koji dodaje userPart), odblokira se.
     *
     * <p><b>P1-margin-1 (R3 1548) — kasni margin call:</b> prag je RASPOLOZIVI
     * initialMargin {@code (IM − reservedMargin)}, ne sirovi IM. Tako je post-fill
     * margin-call konzistentan sa dnevnim scheduler-om
     * ({@link MarginAccountRepository#findEligibleForBlock}) i {@code withdraw}
     * guard-om (P1-8) — racun sa zarobljenom rezervacijom (hold za jos jedan
     * in-flight BUY) blokira se cim raspoloziva marza padne ispod MM.
     */
    private void checkMarginCallAndBlock(MarginAccount margin) {
        BigDecimal availableIm = margin.getAvailableInitialMargin();
        // R1 769: deljena margin-call politika (prag = raspoloziva marza vs MM).
        boolean shouldBlock = MarginCallPolicy.shouldBlock(margin);
        boolean isBlocked = margin.getStatus() == MarginAccountStatus.BLOCKED;

        if (shouldBlock && !isBlocked) {
            margin.setStatus(MarginAccountStatus.BLOCKED);
            marginAccountRepository.save(margin);
            BigDecimal deficit = MarginCallPolicy.deficit(margin);
            // P1-margin-1 (R1 204): ranije se ovde slao email=null → listener bi
            // tiho preskocio notifikaciju (margin-call trgovinom NIKAD nije slao
            // email). Sada razresavamo email vlasnika (UserMarginAccount → banka-core
            // getUserById). Za CompanyMarginAccount banka-core nema company-email
            // lookup, pa ostaje null (dnevni scheduler ima istu granicu) — bar
            // korisnicki margin racuni sada dobijaju email.
            String ownerEmail = resolveMarginOwnerEmail(margin);
            eventPublisher.publishEvent(new MarginAccountBlockedEvent(
                    // R1 381: userId + marginAccountId za in-app (bell) notifikaciju.
                    margin.getUserId(),
                    margin.getId(),
                    ownerEmail,
                    margin.getMaintenanceMargin().toString(),
                    margin.getInitialMargin().toString(),
                    deficit.toString()
            ));
            log.warn("MARGIN CALL (post-fill): account #{} blocked. availableIM={} (IM={}, reserved={}), MM={}",
                    margin.getId(), availableIm, margin.getInitialMargin(),
                    margin.getReservedMargin(), margin.getMaintenanceMargin());
        } else if (!shouldBlock && isBlocked) {
            margin.setStatus(MarginAccountStatus.ACTIVE);
            marginAccountRepository.save(margin);
            log.info("Margin account #{} auto-unblocked: availableIM={} >= MM={}",
                    margin.getId(), availableIm, margin.getMaintenanceMargin());
        }
    }

    /**
     * P1-margin-1 (R1 204): razresava email vlasnika margin racuna za margin-call
     * notifikaciju. UserMarginAccount → banka-core {@code getUserById(CLIENT, userId)}.
     * CompanyMarginAccount (userId==null) → null (banka-core nema company-email
     * lookup bez wire-promene). Best-effort: bilo koji fail vraca null (listener
     * preskace email, ali counter/log ostaju).
     */
    private String resolveMarginOwnerEmail(MarginAccount margin) {
        Long userId = margin.getUserId();
        if (userId == null) {
            return null;
        }
        try {
            return bankaCoreClient.getUserById(rs.raf.trading.common.UserRole.CLIENT, userId).email();
        } catch (RuntimeException ex) {
            log.warn("Margin call (post-fill): nije moguce razresiti email vlasnika {}: {}",
                    userId, ex.getMessage());
            return null;
        }
    }

    /**
     * Razresava id bankinog trading racuna za datu valutu (uvek RSD za margin).
     */
    private Long resolveBankTradingAccountId(String currencyCode) {
        try {
            return bankaCoreClient.getBankTradingAccount(currencyCode).id();
        } catch (BankaCoreClientException ex) {
            throw new IllegalStateException(
                    "Banka nema BANK_TRADING racun u valuti " + currencyCode + ": " + ex.getMessage(), ex);
        }
    }
}
