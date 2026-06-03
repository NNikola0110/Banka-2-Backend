package rs.raf.banka2_bek.otp.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.otp.model.OtpConsumedCode;
import rs.raf.banka2_bek.otp.repository.OtpConsumedCodeRepository;

import java.time.LocalDateTime;

/**
 * N2-a (V-1) — write-ahead consume zapisivac za single-use OTP store.
 *
 * <p>{@link OtpService#verify} radi u tranzakciji {@code REQUIRED} koja se cesto
 * pokrece UNUTAR caller-ove biznis tranzakcije
 * ({@code SavingsDepositService.openDeposit}, {@code LoanServiceImpl.createLoanRequest}/
 * {@code earlyRepayment}, {@code PaymentServiceImpl.quickApprove} — sve su
 * {@code @Transactional}). Ako biznis logika padne POSLE uspesne OTP provere
 * (npr. insufficient funds), rollback bi obrisao i consume zapis → isti OTP bi
 * postao ponovo upotrebljiv (replay).</p>
 *
 * <p>Zato se consume save izvrsava u {@code REQUIRES_NEW} tranzakciji: suspenduje
 * caller-ovu tranzakciju i commit-uje potroseni kod NEZAVISNO. Tako uspesan TOTP
 * ostaje potrosen i kad biznis-validacija kasnije padne. Bezbednosni tradeoff je
 * ispravan za single-use semantiku: korisnik koji prodje OTP ali padne na
 * biznis-validaciji mora zatraziti NOV OTP.</p>
 *
 * <p>Mora biti zaseban Spring bean (ne metoda u {@link OtpService}) jer
 * self-invocation ne prolazi kroz proxy — bez proxy-ja {@code REQUIRES_NEW} ne bi
 * imao efekta i save bi opet bio u caller-ovoj tranzakciji.</p>
 */
@Component
public class OtpConsumedCodeWriter {

    private final OtpConsumedCodeRepository otpConsumedCodeRepository;

    public OtpConsumedCodeWriter(OtpConsumedCodeRepository otpConsumedCodeRepository) {
        this.otpConsumedCodeRepository = otpConsumedCodeRepository;
    }

    /**
     * Persistuje potroseni kod u NOVOJ tranzakciji (commit nezavisan od caller-a).
     *
     * @throws DataIntegrityViolationException ako (user_id, code_hash) vec postoji
     *         (konkurentni replay) — caller to mapira u "vec iskoriscen".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(Long userId, String codeHash, String intentHash) {
        otpConsumedCodeRepository.save(OtpConsumedCode.builder()
                .userId(userId)
                .codeHash(codeHash)
                .intentHash(intentHash)
                .consumedAt(LocalDateTime.now())
                .build());
    }
}
