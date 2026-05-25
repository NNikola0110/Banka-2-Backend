package rs.raf.banka2_bek.otp;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.otp.model.TotpSecret;
import rs.raf.banka2_bek.otp.repository.TotpSecretRepository;
import rs.raf.banka2_bek.otp.service.TotpService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TotpServiceTest {

    @Mock
    private TotpSecretRepository totpSecretRepository;

    private TotpService totpService;

    private static final Long USER_ID = 42L;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        totpService = new TotpService(totpSecretRepository);
    }

    @Test
    @DisplayName("generateSecret persists new secret and returns non-blank base32")
    void generateSecret_createsAndPersistsSecret() {
        String key = totpService.generateSecret(USER_ID);

        verify(totpSecretRepository).deleteByUserId(USER_ID);

        ArgumentCaptor<TotpSecret> captor = ArgumentCaptor.forClass(TotpSecret.class);
        verify(totpSecretRepository).save(captor.capture());

        TotpSecret saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getSecret()).isNotBlank();
        assertThat(key).isNotBlank().isEqualTo(saved.getSecret());
    }

    @Test
    @DisplayName("generateSecret deletes existing secret before saving new")
    void generateSecret_rotatesExistingSecret() {
        totpService.generateSecret(USER_ID);

        InOrder order = inOrder(totpSecretRepository);
        order.verify(totpSecretRepository).deleteByUserId(USER_ID);
        order.verify(totpSecretRepository).save(any(TotpSecret.class));
    }

    @Test
    @DisplayName("verify returns true for valid current-window code")
    void verify_returnsTrueForValidCode() {
        GoogleAuthenticatorKey key = new GoogleAuthenticator().createCredentials();
        String secret = key.getKey();
        when(totpSecretRepository.findByUserId(eq(USER_ID)))
                .thenReturn(Optional.of(buildSecret(secret)));

        int validCode = new GoogleAuthenticator().getTotpPassword(secret);

        assertThat(totpService.verify(USER_ID, String.format("%06d", validCode))).isTrue();
    }

    @Test
    @DisplayName("verify returns false for invalid code")
    void verify_returnsFalseForInvalidCode() {
        GoogleAuthenticatorKey key = new GoogleAuthenticator().createCredentials();
        when(totpSecretRepository.findByUserId(eq(USER_ID)))
                .thenReturn(Optional.of(buildSecret(key.getKey())));

        assertThat(totpService.verify(USER_ID, "000000")).isFalse();
    }

    @Test
    @DisplayName("verify returns false for non-numeric code without throwing")
    void verify_returnsFalseForNonNumericCode() {
        GoogleAuthenticatorKey key = new GoogleAuthenticator().createCredentials();
        when(totpSecretRepository.findByUserId(eq(USER_ID)))
                .thenReturn(Optional.of(buildSecret(key.getKey())));

        assertThat(totpService.verify(USER_ID, "abc")).isFalse();
    }

    @Test
    @DisplayName("verify throws IllegalStateException when no secret configured")
    void verify_throwsWhenSecretNotFound() {
        when(totpSecretRepository.findByUserId(eq(USER_ID)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> totpService.verify(USER_ID, "123456"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nije podesen");
    }

    @Test
    @DisplayName("verify accepts code from previous 30s window (tolerance ±1)")
    void verify_acceptsAdjacentWindowCode() {
        GoogleAuthenticatorKey key = new GoogleAuthenticator().createCredentials();
        String secret = key.getKey();
        when(totpSecretRepository.findByUserId(eq(USER_ID)))
                .thenReturn(Optional.of(buildSecret(secret)));

        long prevWindowMillis = System.currentTimeMillis() - 30_000L;
        int prevCode = new GoogleAuthenticator().getTotpPassword(secret, prevWindowMillis);

        assertThat(totpService.verify(USER_ID, String.format("%06d", prevCode))).isTrue();
    }

    private TotpSecret buildSecret(String secret) {
        return TotpSecret.builder()
                .userId(USER_ID)
                .secret(secret)
                .build();
    }
}
