package rs.raf.notification.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import rs.raf.notification.mail.template.AccountCreatedConfirmationEmailTemplate;
import rs.raf.notification.mail.template.ActivationConfirmedEmailTemplate;
import rs.raf.notification.mail.template.ActivationEmailTemplate;
import rs.raf.notification.mail.template.OtpEmailTemplate;
import rs.raf.notification.mail.template.PasswordResetEmailTemplate;
import rs.raf.notification.mail.template.TransactionEmailTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * TODO [B2 + B4 - Email notifikacije]
 *
 * [B2] Dodati metodu za slanje email obavestenja korisniku kada mu se nalog
 *      zakljuca zbog previse neuspesnih pokusaja prijave. Email treba da sadrzi:
 *      - obavestenje da je nalog privremeno zakljucan (sa trajanjem zakljucavanja),
 *      - opciju/link za resetovanje lozinke kako bi korisnik mogao da povrati pristup.
 *      Metoda se okida iz AccountLockoutService u trenutku kada se nalog zakljuca
 *      (tj. kada recordFailure() dostigne maxFailedAttempts i baci AccountLockedException).
 *
 * [B4] Prosiriti notifikacioni sistem novim email sablonima za poslovne dogadjaje:
 *      - Placanje: potvrda uspesnog placanja (sa iznosom, primaocem i datumom).
 *      - Transfer: potvrda medjunarodnog/deviznog transfera.
 *      - Promena limita: obavestenje o promeni dnevnog/mesecnog limita na racunu.
 *      - Blokada kartice: obavestenje kada zaposleni blokira karticu (vec postoji
 *        sendCardBlockedMail, prosiriti sadrzaj po potrebi B4 sablonom).
 *      - Kreiranje kredita: potvrda podnosenja zahteva za kredit.
 *      - Odobravanje/odbijanje kredita: odgovor banke na zahtev.
 *      - Lifecycle ordera: obavestenja o statusu ordera (APPROVED, DONE, DECLINED).
 *      - OTC dogadjaji: obavestenje o primljenoj OTC ponudi, prihvatanju, kontra-ponudi,
 *        isteku ugovora i iskoristavanju opcijskog ugovora (exercise).
 *      Svaki sablon treba da bude implementiran kao zaseban Spring bean (po uzoru na
 *      postojece sablone u notification/template/) i injektovan u ovaj servis.
 */
@Service
public class MailNotificationService {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String passwordResetUrlBase;
    private final String passwordResetPagePath;
    private final String activationUrlBase;
    private final String activationPagePath;
    private final PasswordResetEmailTemplate passwordResetEmailTemplate;
    private final ActivationEmailTemplate activationEmailTemplate;
    private final ActivationConfirmedEmailTemplate activationConfirmedEmailTemplate;
    private final AccountCreatedConfirmationEmailTemplate accountCreatedConfirmationEmailTemplate;
    private final OtpEmailTemplate otpEmailTemplate;
    private final TransactionEmailTemplate transactionEmailTemplate;

    public MailNotificationService(JavaMailSender mailSender,
                                   PasswordResetEmailTemplate passwordResetEmailTemplate,
                                   ActivationEmailTemplate activationEmailTemplate,
                                   ActivationConfirmedEmailTemplate activationConfirmedEmailTemplate,
                                   AccountCreatedConfirmationEmailTemplate accountCreatedConfirmationEmailTemplate,
                                   OtpEmailTemplate otpEmailTemplate,
                                   TransactionEmailTemplate transactionEmailTemplate,
                                   @Value("${spring.mail.username}") String fromAddress,
                                   @Value("${notification.password-reset-url-base}") String passwordResetUrlBase,
                                   @Value("${notification.password-reset-page-path:/reset-password}") String passwordResetPagePath,
                                   @Value("${notification.activation-url-base}") String activationUrlBase,
                                   @Value("${notification.activation-page-path:/activate-account}") String activationPagePath) {
        this.mailSender = mailSender;
        this.passwordResetEmailTemplate = passwordResetEmailTemplate;
        this.activationEmailTemplate = activationEmailTemplate;
        this.activationConfirmedEmailTemplate = activationConfirmedEmailTemplate;
        this.fromAddress = fromAddress;
        this.passwordResetUrlBase = passwordResetUrlBase;
        this.passwordResetPagePath = passwordResetPagePath;
        this.activationUrlBase = activationUrlBase;
        this.activationPagePath = activationPagePath;
        this.accountCreatedConfirmationEmailTemplate = accountCreatedConfirmationEmailTemplate;
        this.otpEmailTemplate = otpEmailTemplate;
        this.transactionEmailTemplate = transactionEmailTemplate;
    }

    public void sendPasswordResetMail(String toEmail, String token) {
        String resetLink = passwordResetUrlBase + passwordResetPagePath + "?token=" + token;
        String subject = passwordResetEmailTemplate.buildSubject();
        String html = passwordResetEmailTemplate.buildBody(resetLink);

        HtmlMailSender.sendHtmlMail(mailSender, fromAddress, toEmail, subject, html);
    }

    public void sendActivationMail(String toEmail, String firstName, String token) {
        String activationLink = activationUrlBase + activationPagePath + "?token=" + token;
        String subject = activationEmailTemplate.buildSubject();
        String html = activationEmailTemplate.buildBody(activationLink, firstName);

        HtmlMailSender.sendHtmlMail(mailSender, fromAddress, toEmail, subject, html);
    }

    public void sendActivationConfirmationMail(String toEmail, String firstName) {
        String subject = activationConfirmedEmailTemplate.buildSubject();
        String html = activationConfirmedEmailTemplate.buildBody(firstName);

        HtmlMailSender.sendHtmlMail(mailSender, fromAddress, toEmail, subject, html);
    }

    public void sendAccountCreatedConfirmationMail(String toEmail, String firstName, String accountNumber, String accountType) {
        String subject = accountCreatedConfirmationEmailTemplate.buildSubject();
        String html = accountCreatedConfirmationEmailTemplate.buildBody(firstName, accountNumber, accountType);

        HtmlMailSender.sendHtmlMail(mailSender, fromAddress, toEmail, subject, html);
    }

    public void sendOtpMail(String toEmail, String code, int expiryMinutes) {
        String subject = otpEmailTemplate.buildSubject();
        String html = otpEmailTemplate.buildBody(code, expiryMinutes);

        HtmlMailSender.sendHtmlMail(mailSender, fromAddress, toEmail, subject, html);
    }

    public void sendPaymentConfirmationMail(String toEmail, BigDecimal amount, String currency,
                                            String fromAccount, String toAccount,
                                            LocalDate date, String status) {
        String subject = transactionEmailTemplate.buildPaymentSubject();
        String html = transactionEmailTemplate.buildPaymentBody(amount, currency, fromAccount, toAccount, date, status);
        HtmlMailSender.sendHtmlMail(mailSender, fromAddress, toEmail, subject, html);
    }

    public void sendCardBlockedMail(String toEmail, String last4Digits, LocalDate blockDate) {
        String subject = transactionEmailTemplate.buildCardBlockedSubject();
        String html = transactionEmailTemplate.buildCardBlockedBody(last4Digits, blockDate);
        HtmlMailSender.sendHtmlMail(mailSender, fromAddress, toEmail, subject, html);
    }

    public void sendCardUnblockedMail(String toEmail, String last4Digits) {
        String subject = transactionEmailTemplate.buildCardUnblockedSubject();
        String html = transactionEmailTemplate.buildCardUnblockedBody(last4Digits);
        HtmlMailSender.sendHtmlMail(mailSender, fromAddress, toEmail, subject, html);
    }

    public void sendLoanRequestSubmittedMail(String toEmail, String loanType,
                                             BigDecimal amount, String currency) {
        String subject = transactionEmailTemplate.buildLoanRequestSubject();
        String html = transactionEmailTemplate.buildLoanRequestBody(loanType, amount, currency);
        HtmlMailSender.sendHtmlMail(mailSender, fromAddress, toEmail, subject, html);
    }

    public void sendLoanApprovedMail(String toEmail, String loanNumber, BigDecimal amount,
                                     String currency, BigDecimal monthlyPayment, LocalDate startDate) {
        String subject = transactionEmailTemplate.buildLoanApprovedSubject();
        String html = transactionEmailTemplate.buildLoanApprovedBody(loanNumber, amount, currency, monthlyPayment, startDate);
        HtmlMailSender.sendHtmlMail(mailSender, fromAddress, toEmail, subject, html);
    }

    public void sendLoanRejectedMail(String toEmail, String loanType,
                                     BigDecimal amount, String currency) {
        String subject = transactionEmailTemplate.buildLoanRejectedSubject();
        String html = transactionEmailTemplate.buildLoanRejectedBody(loanType, amount, currency);
        HtmlMailSender.sendHtmlMail(mailSender, fromAddress, toEmail, subject, html);
    }

    public void sendInstallmentPaidMail(String toEmail, String loanNumber,
                                        BigDecimal installmentAmount, String currency,
                                        BigDecimal remainingDebt) {
        String subject = transactionEmailTemplate.buildInstallmentPaidSubject();
        String html = transactionEmailTemplate.buildInstallmentPaidBody(loanNumber, installmentAmount, currency, remainingDebt);
        HtmlMailSender.sendHtmlMail(mailSender, fromAddress, toEmail, subject, html);
    }

    public void sendInstallmentFailedMail(String toEmail, String loanNumber,
                                          BigDecimal amountDue, String currency,
                                          LocalDate nextRetryDate) {
        String subject = transactionEmailTemplate.buildInstallmentFailedSubject();
        String html = transactionEmailTemplate.buildInstallmentFailedBody(loanNumber, amountDue, currency, nextRetryDate);
        HtmlMailSender.sendHtmlMail(mailSender, fromAddress, toEmail, subject, html);
    }
}

