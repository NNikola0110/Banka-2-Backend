package rs.raf.notification.mail.template;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
public class TransactionEmailTemplate {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy.");

    /** [P1-i18n-1 / 1853] fiksiran sr-RS Locale za novcani format (deterministicki
     *  grouping/decimal separator nezavisno od JVM/kontejnerskog lokala). */
    private static final Locale SR_RS = Locale.forLanguageTag("sr-RS");

    // ── 1. Payment confirmation ──────────────────────────────────────

    public String buildPaymentSubject() {
        return "Potvrda plaćanja - Banka 2";
    }

    public String buildPaymentBody(BigDecimal amount, String currency,
                                   String fromAccount, String toAccount,
                                   LocalDate date, String status) {
        return wrapHtml("Potvrda plaćanja", "Plaćanje uspešno izvršeno",
                """
                <p style="margin:0 0 20px 0;font-size:14px;color:#4b5563;line-height:1.6;">
                    Vaše plaćanje je uspešno obrađeno. Ispod su detalji transakcije:
                </p>
                """ + detailsTable(
                        row("Iznos", formatAmount(amount, currency)),
                        row("Sa računa", fromAccount),
                        row("Na račun", toAccount),
                        row("Datum", date.format(DATE_FMT)),
                        row("Status", status)
                ));
    }

    // ── 2. Card blocked ──────────────────────────────────────────────

    public String buildCardBlockedSubject() {
        return "Kartica blokirana - Banka 2";
    }

    public String buildCardBlockedBody(String last4Digits, LocalDate blockDate) {
        return wrapHtml("Kartica blokirana", "Obaveštenje o blokadi kartice",
                """
                <p style="margin:0 0 20px 0;font-size:14px;color:#4b5563;line-height:1.6;">
                    Vaša kartica je blokirana. Ako niste zahtevali blokadu, molimo kontaktirajte podršku.
                </p>
                """ + detailsTable(
                        row("Kartica", "•••• " + last4Digits),
                        row("Datum blokade", blockDate.format(DATE_FMT))
                ));
    }

    // ── 3. Card unblocked ────────────────────────────────────────────

    public String buildCardUnblockedSubject() {
        return "Kartica deblokirana - Banka 2";
    }

    public String buildCardUnblockedBody(String last4Digits) {
        return wrapHtml("Kartica deblokirana", "Obaveštenje o deblokadi kartice",
                """
                <p style="margin:0 0 20px 0;font-size:14px;color:#4b5563;line-height:1.6;">
                    Vaša kartica je uspešno deblokirana i ponovo je aktivna za korišćenje.
                </p>
                """ + detailsTable(
                        row("Kartica", "•••• " + last4Digits)
                ));
    }

    // ── 4. Loan request submitted ────────────────────────────────────

    public String buildLoanRequestSubject() {
        return "Zahtev za kredit primljen - Banka 2";
    }

    public String buildLoanRequestBody(String loanType, BigDecimal amount, String currency) {
        return wrapHtml("Zahtev za kredit", "Zahtev za kredit je primljen",
                """
                <p style="margin:0 0 20px 0;font-size:14px;color:#4b5563;line-height:1.6;">
                    Vaš zahtev za kredit je uspešno podnet i čeka odobrenje. Bićete obavešteni o statusu.
                </p>
                """ + detailsTable(
                        row("Tip kredita", loanType),
                        row("Iznos", formatAmount(amount, currency)),
                        row("Status", "Na čekanju")
                ));
    }

    // ── 5. Loan approved ─────────────────────────────────────────────

    public String buildLoanApprovedSubject() {
        return "Kredit odobren - Banka 2";
    }

    public String buildLoanApprovedBody(String loanNumber, BigDecimal amount, String currency,
                                        BigDecimal monthlyPayment, LocalDate startDate) {
        return wrapHtml("Kredit odobren", "Vaš kredit je odobren",
                """
                <p style="margin:0 0 20px 0;font-size:14px;color:#4b5563;line-height:1.6;">
                    Čestitamo! Vaš zahtev za kredit je odobren. Sredstva su prebačena na Vaš račun.
                </p>
                """ + detailsTable(
                        row("Broj kredita", loanNumber),
                        row("Iznos", formatAmount(amount, currency)),
                        row("Mesečna rata", formatAmount(monthlyPayment, currency)),
                        row("Početak otplate", startDate.format(DATE_FMT))
                ));
    }

    // ── 6. Loan rejected ─────────────────────────────────────────────

    public String buildLoanRejectedSubject() {
        return "Zahtev za kredit odbijen - Banka 2";
    }

    public String buildLoanRejectedBody(String loanType, BigDecimal amount, String currency) {
        return wrapHtml("Zahtev odbijen", "Vaš zahtev za kredit je odbijen",
                """
                <p style="margin:0 0 20px 0;font-size:14px;color:#4b5563;line-height:1.6;">
                    Nažalost, Vaš zahtev za kredit nije odobren. Za više informacija, kontaktirajte podršku.
                </p>
                """ + detailsTable(
                        row("Tip kredita", loanType),
                        row("Traženi iznos", formatAmount(amount, currency)),
                        row("Status", "Odbijen")
                ));
    }

    // ── 7. Installment paid ──────────────────────────────────────────

    public String buildInstallmentPaidSubject() {
        return "Rata kredita plaćena - Banka 2";
    }

    public String buildInstallmentPaidBody(String loanNumber, BigDecimal installmentAmount,
                                           String currency, BigDecimal remainingDebt) {
        return wrapHtml("Rata plaćena", "Rata kredita uspešno naplaćena",
                """
                <p style="margin:0 0 20px 0;font-size:14px;color:#4b5563;line-height:1.6;">
                    Rata za Vaš kredit je uspešno naplaćena sa Vašeg računa.
                </p>
                """ + detailsTable(
                        row("Broj kredita", loanNumber),
                        row("Iznos rate", formatAmount(installmentAmount, currency)),
                        row("Preostali dug", formatAmount(remainingDebt, currency))
                ));
    }

    // ── 8. Installment failed ────────────────────────────────────────

    public String buildInstallmentFailedSubject() {
        return "Neuspešna naplata rate - Banka 2";
    }

    public String buildInstallmentFailedBody(String loanNumber, BigDecimal amountDue,
                                             String currency, LocalDate nextRetryDate) {
        return wrapHtml("Neuspešna naplata", "Naplata rate nije uspela",
                """
                <p style="margin:0 0 20px 0;font-size:14px;color:#4b5563;line-height:1.6;">
                    Naplata rate za Vaš kredit nije uspela zbog nedovoljno sredstava na računu.
                    Molimo obezbedite dovoljno sredstava pre sledećeg pokušaja.
                </p>
                """ + detailsTable(
                        row("Broj kredita", loanNumber),
                        row("Dugovani iznos", formatAmount(amountDue, currency)),
                        row("Sledeći pokušaj", nextRetryDate.format(DATE_FMT))
                ));
    }

    // ── Private helpers ──────────────────────────────────────────────

    private String formatAmount(BigDecimal amount, String currency) {
        if (amount == null) return "-";
        // NB: rezultat se uvek prosledjuje u row(...) koji HTML-escape-uje value
        // ([P1-notif-svc-1 / 1528]) — currency NE escape-ujemo ovde da izbegnemo
        // dvostruki escape.
        // [P1-i18n-1 / 1853] eksplicitan sr-RS Locale za grouping/decimal separator
        // (nezavisno od JVM lokala kontejnera).
        return String.format(SR_RS, "%,.2f %s", amount, currency != null ? currency : "");
    }

    private String row(String label, String value) {
        // [P1-notif-svc-1 / 1528] value je user-controlled (fromAccount/toAccount/
        // status/loanType/loanNumber...) → HTML escape pre umetanja. label je uvek
        // hardkodiran literal ali se escape-uje radi konzistentnosti.
        return """
                <tr>
                    <td style="padding:14px 20px;border-bottom:1px solid #c7d2fe;">
                        <table role="presentation" cellpadding="0" cellspacing="0" width="100%%">
                            <tr>
                                <td style="font-size:12px;color:#6b7280;text-align:left;">%s</td>
                                <td style="font-size:13px;font-weight:600;color:#4338ca;text-align:right;">%s</td>
                            </tr>
                        </table>
                    </td>
                </tr>
                """.formatted(EmailHtml.escape(label), EmailHtml.escape(value));
    }

    private String detailsTable(String... rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 auto 24px auto;background-color:#eef2ff;border-radius:12px;border:1px solid #c7d2fe;padding:0;overflow:hidden;width:100%%;">
                """);
        for (String r : rows) {
            sb.append(r);
        }
        sb.append("</table>\n");
        return sb.toString();
    }

    private String wrapHtml(String headerTitle, String h1Title, String bodyContent) {
        return """
                <!DOCTYPE html>
                <html lang="sr">
                <head>
                    <meta charset="UTF-8">
                    <title>%s</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f8fafc;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                <table role="presentation" cellpadding="0" cellspacing="0" width="100%%%%" style="padding:32px 0;">
                    <tr>
                        <td align="center">
                            <table role="presentation" cellpadding="0" cellspacing="0" width="100%%%%" style="max-width:520px;background-color:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 20px 50px rgba(99,102,241,0.18);border:1px solid #e5e7eb;">
                                <tr>
                                    <td style="background:linear-gradient(135deg,#6366f1,#7c3aed);padding:28px 24px;text-align:center;">
                                        <p style="margin:0 0 4px 0;font-size:13px;font-weight:500;color:rgba(255,255,255,0.7);letter-spacing:0.08em;text-transform:uppercase;">Banka 2</p>
                                        <h1 style="margin:0;font-size:22px;font-weight:700;color:#ffffff;letter-spacing:0.01em;">%s</h1>
                                    </td>
                                </tr>
                                <tr>
                                    <td style="padding:32px 28px;text-align:center;">
                                        %s
                                    </td>
                                </tr>
                                <tr>
                                    <td style="padding:16px 24px;border-top:1px solid #e5e7eb;background-color:#f9fafb;">
                                        <p style="margin:0;font-size:11px;color:#9ca3af;text-align:center;">
                                            Ovo je automatska poruka od Banka 2. Molimo ne odgovarajte na ovaj email.
                                        </p>
                                    </td>
                                </tr>
                            </table>
                        </td>
                    </tr>
                </table>
                </body>
                </html>
                """.formatted(headerTitle, h1Title, bodyContent);
    }
}
