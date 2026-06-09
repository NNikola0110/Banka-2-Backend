package rs.raf.banka2_bek.interbank.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.currency.repository.CurrencyRepository;
import rs.raf.banka2_bek.payment.model.Payment;
import rs.raf.banka2_bek.payment.model.PaymentStatus;
import rs.raf.banka2_bek.payment.repository.PaymentRepository;

import java.math.BigDecimal;

/**
 * Belezi INCOMING {@link Payment} red kad nasa banka (Banka B) PRIMI novac preko
 * inter-bank 2PC protokola (inbound COMMIT_TX koji kreditira lokalni racun).
 *
 * <p><b>Zasto postoji:</b> outbound placanja (B2 → druga banka) kreiraju Payment red
 * unapred u {@code PaymentServiceImpl.createInterbankPayment}, pa se vide u istoriji.
 * Inbound primici (druga banka → B2) su do sada SAMO kreditirali racun (kroz
 * {@code InterbankReservationApplier.commitRecipientCredit}) bez ijednog Payment reda —
 * pa primalac nikad nije video uplatu u {@code GET /payments} istoriji
 * ({@code PaymentServiceImpl.getPaymentHistory} cita {@code payments} tabelu). Ova
 * klasa zatvara tu rupu: tacno JEDAN INCOMING Payment red po primljenom kreditu.
 *
 * <p><b>Money-safety / 2PC izolacija:</b> metoda je {@link Propagation#REQUIRES_NEW} —
 * upis Payment reda ide u SVOJOJ transakciji, NEZAVISNO od money-moving
 * {@code commitLocal} transakcije. Greska ovde (npr. DataIntegrityViolation) ne sme da
 * rollback-uje stvarni kredit primaocu — istorijski zapis je best-effort, prenos novca
 * je autoritativan. Pozivalac (u {@code TransactionExecutorService.commitLocal}) dodatno
 * hvata svaki izuzetak i samo loguje warning.
 *
 * <p><b>Idempotencija:</b> isti COMMIT_TX moze biti retransmitovan (§2.9). Pre upisa
 * proveravamo postoji li vec Payment sa istim (interbankTxRoutingNumber,
 * interbankTxIdString, toAccountNumber); ako da — no-op. Determinisitcki, kratak
 * {@code orderNumber} izveden iz tx para + racuna sluzi kao druga linija odbrane
 * (UNIQUE na order_number) za trku dva paralelna COMMIT_TX-a: drugi insert padne na
 * DataIntegrityViolation koju gutamo kao "vec zabelezeno".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterbankInboundPaymentRecorder {

    private final PaymentRepository paymentRepository;
    private final AccountRepository accountRepository;
    private final CurrencyRepository currencyRepository;

    /**
     * Belezi jednu INCOMING uplatu na lokalni primaocev racun.
     *
     * @param routingNumber       2PC tx routing broj (inbound transactionId.routingNumber)
     * @param txIdString          2PC tx id string (inbound transactionId.id)
     * @param toAccountNumber     kreditirani lokalni B2 racun (primalac)
     * @param amount              kreditirani iznos (apsolutna vrednost wire posting-a)
     * @param postingCurrencyCode ISO kod wire valute iz kreditnog posting-a (npr. "EUR")
     * @param recipientName       best-effort naziv posiljaoca (display-only)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIncoming(Integer routingNumber, String txIdString, String toAccountNumber,
                               BigDecimal amount, String postingCurrencyCode, String recipientName) {
        if (toAccountNumber == null || amount == null) {
            return;
        }

        // Idempotency: ne dupliraj istorijski zapis na retransmit COMMIT_TX-a (§2.9).
        if (paymentRepository.existsByInterbankTxRoutingNumberAndInterbankTxIdStringAndToAccountNumber(
                routingNumber, txIdString, toAccountNumber)) {
            log.debug("Inbound payment record vec postoji za tx {}/{} -> {} — preskacem (idempotent).",
                    routingNumber, txIdString, toAccountNumber);
            return;
        }

        Account recipientAccount = accountRepository.findByAccountNumber(toAccountNumber).orElse(null);

        // Valuta reda = valuta primaocevog racuna ako je razresiva (cross-currency inbound:
        // primalac je kreditiran u valuti SVOG racuna, ne u wire valuti); inace fallback na
        // wire valutu iz posting-a.
        Currency currency = null;
        if (recipientAccount != null && recipientAccount.getCurrency() != null) {
            currency = recipientAccount.getCurrency();
        } else if (postingCurrencyCode != null) {
            currency = currencyRepository.findByCode(postingCurrencyCode).orElse(null);
        }
        if (currency == null) {
            log.warn("Inbound payment record: ne mogu da razresim valutu za tx {}/{} -> {} "
                    + "(racun null? {}, wire ccy {}). Preskacem zapis (kredit je vec primenjen).",
                    routingNumber, txIdString, toAccountNumber, recipientAccount == null, postingCurrencyCode);
            return;
        }

        // createdBy = vlasnik kreditiranog racuna ako je klijent; null za kompanijski/nerazresiv.
        // Vidljivost u istoriji vodi se preko toAccountNumber vlasnistva (isPartyToPayment),
        // ne preko createdBy, pa je null prihvatljiv.
        var createdBy = (recipientAccount != null) ? recipientAccount.getClient() : null;

        Payment payment = Payment.builder()
                .orderNumber(deterministicOrderNumber(routingNumber, txIdString, toAccountNumber))
                .fromAccount(null)                 // posiljalac je u drugoj banci → INCOMING
                .toAccountNumber(toAccountNumber)
                .amount(amount)
                .fee(BigDecimal.ZERO)
                .currency(currency)
                .recipientName(recipientName)
                .purpose("Inter-bank uplata")
                .status(PaymentStatus.COMPLETED)
                .createdBy(createdBy)
                .interbankTxIdString(txIdString)
                .interbankTxRoutingNumber(routingNumber)
                .build();

        try {
            paymentRepository.saveAndFlush(payment);
            log.info("Zabelezena INCOMING inter-bank uplata {} {} na {} (tx {}/{}).",
                    amount, currency.getCode(), toAccountNumber, routingNumber, txIdString);
        } catch (DataIntegrityViolationException dup) {
            // Trka: paralelni COMMIT_TX je vec ubacio isti deterministicki orderNumber
            // (ili (tx,racun) jedinstvenost). Tretiramo kao "vec zabelezeno" — no-op.
            log.debug("Inbound payment record duplicate insert za tx {}/{} -> {} (UNIQUE) — vec zabelezeno.",
                    routingNumber, txIdString, toAccountNumber);
        }
    }

    /**
     * Determinisitcki {@code orderNumber} (<= 30 char, kolona limit) izveden iz 2PC tx
     * para + primaocevog racuna. Isti primljeni kredit uvek mapira na isti broj naloga,
     * pa retransmit COMMIT_TX-a padne na UNIQUE order_number umesto da kreira duplikat.
     * Format: {@code IBIN-<routing>-<hex8>} gde je hex8 prvih 8 hex cifara apsolutnog
     * hash-a (txId + racun).
     */
    private static String deterministicOrderNumber(Integer routingNumber, String txIdString,
                                                    String toAccountNumber) {
        int h = (String.valueOf(txIdString) + "|" + toAccountNumber).hashCode();
        String hex8 = String.format("%08x", h);
        return "IBIN-" + routingNumber + "-" + hex8;
    }
}
