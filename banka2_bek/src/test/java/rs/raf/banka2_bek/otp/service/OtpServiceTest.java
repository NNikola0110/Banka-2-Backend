package rs.raf.banka2_bek.otp.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.auth.model.User;
import rs.raf.banka2_bek.auth.repository.UserRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.notification.NotificationPublisher;
import rs.raf.banka2_bek.otp.model.TotpSecret;
import rs.raf.banka2_bek.otp.repository.OtpConsumedCodeRepository;
import rs.raf.banka2_bek.otp.repository.TotpSecretRepository;

import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private TotpService totpService;
    @Mock private TotpSecretRepository totpSecretRepository;
    @Mock private NotificationPublisher notificationPublisher;
    @Mock private OtpConsumedCodeRepository otpConsumedCodeRepository;
    @Mock private OtpConsumedCodeWriter otpConsumedCodeWriter;

    private OtpService otpService;

    private static final int EMAIL_EXPIRY_MINUTES = 5;
    private static final int MAX_FAILED_ATTEMPTS = 3; // R5-365: otp.max-attempts default
    private static final String EMAIL = "user@test.com";
    private static final Long USER_ID = 7L;
    // P1-auth-2 (R2 1364): employee subject-id se offset-uje u disjunktan prostor.
    private static final String EMPLOYEE_EMAIL = "employee@test.com";
    private static final Long EMPLOYEE_ID = 7L; // namerno isti broj kao USER_ID — testira disjunktnost
    private static final long EMPLOYEE_SUBJECT_ID_OFFSET = 1_000_000_000_000L;
    private static final long EMPLOYEE_SUBJECT_ID = EMPLOYEE_SUBJECT_ID_OFFSET + EMPLOYEE_ID;

    @BeforeEach
    void setUp() {
        otpService = new OtpService(
                userRepository, employeeRepository, totpService, totpSecretRepository, notificationPublisher,
                otpConsumedCodeRepository, otpConsumedCodeWriter, EMAIL_EXPIRY_MINUTES, MAX_FAILED_ATTEMPTS);
    }

    private User user() {
        User u = new User();
        u.setId(USER_ID);
        u.setEmail(EMAIL);
        return u;
    }

    private Employee employee() {
        return Employee.builder().id(EMPLOYEE_ID).email(EMPLOYEE_EMAIL).build();
    }

    @Nested
    @DisplayName("generateAndSend")
    class GenerateAndSend {

        @Test
        @DisplayName("generates new TOTP secret when user has none, sends no email")
        void generatesSecretWhenMissing() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            when(totpSecretRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(totpService.generateSecret(USER_ID)).thenReturn("NEWSECRET");

            otpService.generateAndSend(EMAIL);

            verify(totpService).generateSecret(USER_ID);
            verifyNoInteractions(notificationPublisher);
        }

        @Test
        @DisplayName("keeps existing secret when user already has one")
        void keepsExistingSecret() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            when(totpSecretRepository.findByUserId(USER_ID))
                    .thenReturn(Optional.of(TotpSecret.builder().userId(USER_ID).secret("EXIST").build()));

            otpService.generateAndSend(EMAIL);

            verify(totpService, never()).generateSecret(USER_ID);
            verifyNoInteractions(notificationPublisher);
        }

        @Test
        @DisplayName("throws when user not found")
        void throwsWhenUserMissing() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> otpService.generateAndSend(EMAIL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Korisnik nije pronadjen");
        }
    }

    @Nested
    @DisplayName("generateAndSendViaEmail")
    class GenerateAndSendViaEmail {

        @Test
        @DisplayName("computes current TOTP code and publishes mail via NotificationPublisher")
        void emailsCurrentCode() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            String secret = "JBSWY3DPEHPK3PXP";
            when(totpSecretRepository.findByUserId(USER_ID))
                    .thenReturn(Optional.of(TotpSecret.builder().userId(USER_ID).secret(secret).build()));

            otpService.generateAndSendViaEmail(EMAIL);

            verify(notificationPublisher).sendOtpMail(eq(EMAIL), anyString(), eq(EMAIL_EXPIRY_MINUTES));
        }
    }

    @Nested
    @DisplayName("getActiveOtp")
    class GetActiveOtp {

        @Test
        @DisplayName("returns active=true with 6-digit code and seconds left in 30s window")
        void returnsActiveCode() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            when(totpSecretRepository.findByUserId(USER_ID))
                    .thenReturn(Optional.of(TotpSecret.builder().userId(USER_ID).secret("JBSWY3DPEHPK3PXP").build()));

            Map<String, Object> result = otpService.getActiveOtp(EMAIL);

            assertThat(result.get("active")).isEqualTo(true);
            assertThat((String) result.get("code")).matches("\\d{6}");
            assertThat((long) result.get("expiresInSeconds")).isBetween(1L, 30L);
        }
    }

    @Nested
    @DisplayName("verify")
    class Verify {

        @Test
        @DisplayName("delegates to TotpService and returns verified=true on success")
        void verifiedTrue() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            when(totpService.verify(USER_ID, "123456")).thenReturn(true);

            Map<String, Object> result = otpService.verify(EMAIL, "123456");

            assertThat(result.get("verified")).isEqualTo(true);
            assertThat((String) result.get("message")).contains("uspesno");
            // N2: uspesna verifikacija MORA potrositi (consume) kod u single-use store.
            // N2-a (V-1): consume ide kroz writer (REQUIRES_NEW), ne direktno repo.save.
            verify(otpConsumedCodeWriter).consume(eq(USER_ID), anyString(), eq(null));
        }

        @Test
        @DisplayName("N2: drugi put isti kod (replay) odbijen — verified=false, replayed=true")
        void rejectsReplayOfAlreadyConsumedCode() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            // Single-use guard je PRE TOTP provere — totpService.verify se ne dosegne.
            // Prvi verify uspeo i potrosio kod — drugi put store vec sadrzi (userId, codeHash).
            when(otpConsumedCodeRepository.existsByUserIdAndCodeHash(eq(USER_ID), anyString()))
                    .thenReturn(true);

            Map<String, Object> result = otpService.verify(EMAIL, "123456");

            assertThat(result.get("verified")).isEqualTo(false);
            assertThat(result.get("replayed")).isEqualTo(true);
            assertThat((String) result.get("message")).contains("vec iskoriscen");
            // Ne sme se ponovo potrositi (nema dupli consume).
            verify(otpConsumedCodeWriter, never()).consume(any(), any(), any());
        }

        @Test
        @DisplayName("N2: konkurentni replay — UNIQUE violation na save → odbijen kao replay")
        void rejectsConcurrentReplayViaUniqueViolation() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            when(totpService.verify(USER_ID, "123456")).thenReturn(true);
            // existsBy vraca false (race: drugi thread jos nije commit-ovao), ali
            // REQUIRES_NEW consume udari u UNIQUE constraint — atomicno odbijanje na DB nivou.
            doThrow(new DataIntegrityViolationException("duplicate key uk_otp_consumed_user_code"))
                    .when(otpConsumedCodeWriter).consume(eq(USER_ID), anyString(), eq(null));

            Map<String, Object> result = otpService.verify(EMAIL, "123456");

            assertThat(result.get("verified")).isEqualTo(false);
            assertThat(result.get("replayed")).isEqualTo(true);
            assertThat((String) result.get("message")).contains("vec iskoriscen");
        }

        @Test
        @DisplayName("N2: legitiman jedan-OTP-jedna-transakcija prolazi (consume jednom)")
        void legitimateSingleUseSucceeds() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            when(totpService.verify(USER_ID, "654321")).thenReturn(true);
            when(otpConsumedCodeRepository.existsByUserIdAndCodeHash(eq(USER_ID), anyString()))
                    .thenReturn(false);

            Map<String, Object> result = otpService.verify(EMAIL, "654321");

            assertThat(result.get("verified")).isEqualTo(true);
            verify(otpConsumedCodeWriter).consume(eq(USER_ID), anyString(), eq(null));
        }

        @Test
        @DisplayName("N2: pogresan kod se NE potrosi (nema consume save na mismatch)")
        void wrongCodeIsNotConsumed() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            when(totpService.verify(USER_ID, "111111")).thenReturn(false);

            otpService.verify(EMAIL, "111111");

            verify(otpConsumedCodeWriter, never()).consume(any(), any(), any());
        }

        @Test
        @DisplayName("returns verified=false, blocked=false sa attempts=1 na prvom mismatch-u")
        void verifiedFalse() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            when(totpService.verify(USER_ID, "999999")).thenReturn(false);

            Map<String, Object> result = otpService.verify(EMAIL, "999999");

            assertThat(result.get("verified")).isEqualTo(false);
            assertThat(result.get("blocked")).isEqualTo(false);
            assertThat(result.get("attempts")).isEqualTo(1);
            assertThat(result.get("maxAttempts")).isEqualTo(3);
            assertThat((String) result.get("message")).contains("Pogresan");
        }

        @Test
        @DisplayName("BE-AUTH-01: blokira transakciju posle 3 uzastopna mismatch-a")
        void blocksAfterThreeFailures() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            when(totpService.verify(USER_ID, "999999")).thenReturn(false);

            Map<String, Object> first = otpService.verify(EMAIL, "999999");
            assertThat(first.get("blocked")).isEqualTo(false);
            assertThat(first.get("attempts")).isEqualTo(1);

            Map<String, Object> second = otpService.verify(EMAIL, "999999");
            assertThat(second.get("blocked")).isEqualTo(false);
            assertThat(second.get("attempts")).isEqualTo(2);

            Map<String, Object> third = otpService.verify(EMAIL, "999999");
            assertThat(third.get("verified")).isEqualTo(false);
            assertThat(third.get("blocked")).isEqualTo(true);
            assertThat(third.get("attempts")).isEqualTo(3);
            assertThat((String) third.get("message")).contains("Prekoracen");
        }

        @Test
        @DisplayName("BE-AUTH-01: posle blokade naredne provere ostaju blocked=true i NE povecavaju counter")
        void stickyBlockedAfterThreeFailures() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            when(totpService.verify(USER_ID, "999999")).thenReturn(false);

            otpService.verify(EMAIL, "999999");
            otpService.verify(EMAIL, "999999");
            otpService.verify(EMAIL, "999999");

            Map<String, Object> fourth = otpService.verify(EMAIL, "999999");

            assertThat(fourth.get("verified")).isEqualTo(false);
            assertThat(fourth.get("blocked")).isEqualTo(true);
            // counter ostaje 3 (cap), ne penje se na 4 — block je stabilan u prozoru TTL-a.
            assertThat(fourth.get("attempts")).isEqualTo(3);
        }

        @Test
        @DisplayName("BE-AUTH-01: uspesna verifikacija resetuje counter (sledeci fail je opet attempts=1)")
        void successResetsCounter() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            when(totpService.verify(USER_ID, "999999")).thenReturn(false);
            when(totpService.verify(USER_ID, "123456")).thenReturn(true);

            otpService.verify(EMAIL, "999999");
            otpService.verify(EMAIL, "999999");

            Map<String, Object> success = otpService.verify(EMAIL, "123456");
            assertThat(success.get("verified")).isEqualTo(true);
            assertThat(success.get("attempts")).isEqualTo(0);

            Map<String, Object> nextFail = otpService.verify(EMAIL, "999999");
            assertThat(nextFail.get("attempts")).isEqualTo(1);
            assertThat(nextFail.get("blocked")).isEqualTo(false);
        }

        @Test
        @DisplayName("R5-365: otp.max-attempts je konfigurabilan — sa max=5 blokira tek na 5. pokusaju")
        void maxAttemptsConfigurable() {
            // Zaseban OtpService sa max=5 (umesto default 3) — pinuje da je vrednost
            // sada citana iz @Value("${otp.max-attempts}") a ne hardkodirana konstanta.
            OtpService svc5 = new OtpService(
                    userRepository, employeeRepository, totpService, totpSecretRepository,
                    notificationPublisher, otpConsumedCodeRepository, otpConsumedCodeWriter,
                    EMAIL_EXPIRY_MINUTES, 5);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            when(totpService.verify(USER_ID, "999999")).thenReturn(false);

            for (int i = 1; i <= 4; i++) {
                Map<String, Object> r = svc5.verify(EMAIL, "999999");
                assertThat(r.get("blocked")).as("pokusaj %d sa max=5 ne sme biti blokiran", i).isEqualTo(false);
                assertThat(r.get("maxAttempts")).isEqualTo(5);
            }
            Map<String, Object> fifth = svc5.verify(EMAIL, "999999");
            assertThat(fifth.get("blocked")).isEqualTo(true);
            assertThat(fifth.get("attempts")).isEqualTo(5);
        }

        @Test
        @DisplayName("BE-AUTH-01: missing TOTP secret takodje broji u failed attempts")
        void missingSecretCountsAsFailure() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            when(totpService.verify(USER_ID, "123456"))
                    .thenThrow(new IllegalStateException("TOTP nije podesen za korisnika " + USER_ID));

            Map<String, Object> result = otpService.verify(EMAIL, "123456");

            assertThat(result.get("verified")).isEqualTo(false);
            assertThat(result.get("attempts")).isEqualTo(1);
            assertThat((String) result.get("message")).contains("nije pronadjen");
        }

        @Test
        @DisplayName("returns 'nije pronadjen' when user not found i NE inkrementira counter za nepoznate email-ove")
        void userNotFound() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            Map<String, Object> result = otpService.verify(EMAIL, "123456");

            assertThat(result.get("verified")).isEqualTo(false);
            assertThat(result.get("attempts")).isEqualTo(0);
            assertThat((String) result.get("message")).contains("nije pronadjen");
            verifyNoInteractions(totpService);
        }

        @Test
        @DisplayName("P1-auth-2 (1364): EMPLOYEE OTP se resolvuje (User miss -> Employee) i prolazi verifikaciju")
        void resolvesEmployeeWhenNoUser() {
            // REPRODUKCIJA: ranije verify gledao samo userRepository -> employee-OTP
            // akcija (Arbitro) uvek padala "kod nije pronadjen". Sada se resolvuje Employee.
            when(userRepository.findByEmail(EMPLOYEE_EMAIL)).thenReturn(Optional.empty());
            when(employeeRepository.findByEmail(EMPLOYEE_EMAIL)).thenReturn(Optional.of(employee()));
            // Subject-id MORA biti offset-ovan, ne sirov employee id (disjunktnost od user-a).
            when(totpService.verify(EMPLOYEE_SUBJECT_ID, "246810")).thenReturn(true);
            when(otpConsumedCodeRepository.existsByUserIdAndCodeHash(eq(EMPLOYEE_SUBJECT_ID), anyString()))
                    .thenReturn(false);

            Map<String, Object> result = otpService.verify(EMPLOYEE_EMAIL, "246810");

            assertThat(result.get("verified")).isEqualTo(true);
            // consume i TOTP idu na OFFSET-ovan subject-id (ne raw EMPLOYEE_ID == USER_ID).
            verify(totpService).verify(EMPLOYEE_SUBJECT_ID, "246810");
            verify(otpConsumedCodeWriter).consume(eq(EMPLOYEE_SUBJECT_ID), anyString(), eq(null));
        }

        @Test
        @DisplayName("P1-auth-2 (1364): User ima prednost — kad postoji i User i Employee istog email-a, koristi se User id")
        void userTakesPrecedenceOverEmployee() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user()));
            when(totpService.verify(USER_ID, "135790")).thenReturn(true);

            otpService.verify(EMAIL, "135790");

            // Employee repo se ne konsultuje kad je User pronadjen (User-first).
            verify(totpService).verify(USER_ID, "135790");
            verify(employeeRepository, never()).findByEmail(anyString());
        }
    }

    @Nested
    @DisplayName("ensureSecret (employee fallback)")
    class EnsureSecretEmployee {

        @Test
        @DisplayName("P1-auth-2 (1364): generateAndSend za EMPLOYEE koristi offset-ovan subject-id")
        void generateForEmployeeUsesOffsetId() {
            when(userRepository.findByEmail(EMPLOYEE_EMAIL)).thenReturn(Optional.empty());
            when(employeeRepository.findByEmail(EMPLOYEE_EMAIL)).thenReturn(Optional.of(employee()));
            when(totpSecretRepository.findByUserId(EMPLOYEE_SUBJECT_ID)).thenReturn(Optional.empty());
            when(totpService.generateSecret(EMPLOYEE_SUBJECT_ID)).thenReturn("EMPSECRET");

            otpService.generateAndSend(EMPLOYEE_EMAIL);

            verify(totpService).generateSecret(EMPLOYEE_SUBJECT_ID);
        }

        @Test
        @DisplayName("P1-auth-2 (1364): ensureSecret baca kad ni User ni Employee ne postoje")
        void throwsWhenNeitherUserNorEmployee() {
            when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());
            when(employeeRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> otpService.generateAndSend("ghost@test.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Korisnik nije pronadjen");
        }
    }
}
