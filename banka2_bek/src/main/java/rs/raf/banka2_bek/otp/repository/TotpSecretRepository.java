package rs.raf.banka2_bek.otp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rs.raf.banka2_bek.otp.model.TotpSecret;

import java.util.Optional;

public interface TotpSecretRepository extends JpaRepository<TotpSecret, Long> {

    Optional<TotpSecret> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
