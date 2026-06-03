package rs.raf.trading.investmentfund.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.investmentfund.model.FundDividendDistributionLedger;
import rs.raf.trading.investmentfund.repository.FundDividendDistributionLedgerRepository;

/**
 * <b>P1-2 — write-ahead durabilnost ledger marker-a isplate fondovske dividende.</b>
 *
 * <p>Perzistuje {@link FundDividendDistributionLedger} red u <b>SVOJOJ</b>
 * {@link Propagation#REQUIRES_NEW} transakciji, NEZAVISNO od
 * {@link FundDividendService#distributeDividendsToClients(Long)} outer
 * {@code @Transactional} konteksta. Tako se marker komituje (write-ahead) ODMAH
 * posle uspesnog banka-core transfera i prezivi outer-tx rollback / pad:
 * banka-core novac je vec presao out-of-process, pa marker "klijent placen" mora
 * ostati u bazi i kad se ostala raspodela (npr. 409 na sledecem klijentu)
 * rollback-uje. Sledeci cron run vidi marker preko
 * {@link FundDividendDistributionLedgerRepository#existsByIdempotencyKey(String)}
 * i preskace placenog klijenta → nema double-pay.
 *
 * <p>Mirror {@link rs.raf.trading.otc.saga.service.SagaLogWriter} pattern-a
 * (P1-1) za novcane noge fondovskih dividendi.
 */
@Service
public class FundDividendLedgerWriter {

    private final FundDividendDistributionLedgerRepository ledgerRepository;

    public FundDividendLedgerWriter(FundDividendDistributionLedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    /**
     * Komituje "klijent placen za ovaj priliv" marker u zasebnoj
     * {@code REQUIRES_NEW} transakciji. Pozvati TEK POSLE uspesnog banka-core
     * transfera, tako da marker odrazava stvarno prebacen novac.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPaid(FundDividendDistributionLedger ledger) {
        ledgerRepository.saveAndFlush(ledger);
    }
}
