package rs.raf.banka2_bek.otp.service;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorConfig;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.otp.model.TotpSecret;
import rs.raf.banka2_bek.otp.repository.TotpSecretRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class TotpService {

    private final TotpSecretRepository totpSecretRepository;

    @Transactional
    public String generateSecret(Long userId) {
        totpSecretRepository.deleteByUserId(userId);

        GoogleAuthenticatorKey key = new GoogleAuthenticator().createCredentials();
        String secret = key.getKey();

        TotpSecret entity = TotpSecret.builder()
                .userId(userId)
                .secret(secret)
                .build();
        totpSecretRepository.save(entity);

        return secret;
    }

    @Transactional(readOnly = true)
    public boolean verify(Long userId, String code) {
        TotpSecret secret = totpSecretRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "TOTP nije podesen za korisnika " + userId));

        int parsed;
        try {
            parsed = Integer.parseInt(code);
        } catch (NumberFormatException ex) {
            return false;
        }

        // window size 3 = ±1 30s window tolerance
        GoogleAuthenticatorConfig config = new GoogleAuthenticatorConfig
                .GoogleAuthenticatorConfigBuilder()
                .setWindowSize(3)
                .build();
        return new GoogleAuthenticator(config).authorize(secret.getSecret(), parsed);
    }
}
