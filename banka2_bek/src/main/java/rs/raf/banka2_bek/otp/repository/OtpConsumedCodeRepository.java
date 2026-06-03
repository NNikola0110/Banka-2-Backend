package rs.raf.banka2_bek.otp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.banka2_bek.otp.model.OtpConsumedCode;

import java.time.LocalDateTime;

/**
 * N2 — repozitorijum za single-use OTP store ({@link OtpConsumedCode}).
 */
public interface OtpConsumedCodeRepository extends JpaRepository<OtpConsumedCode, Long> {

    boolean existsByUserIdAndCodeHash(Long userId, String codeHash);

    @Modifying
    @Query("DELETE FROM OtpConsumedCode c WHERE c.consumedAt < :cutoff")
    int deleteConsumedOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
