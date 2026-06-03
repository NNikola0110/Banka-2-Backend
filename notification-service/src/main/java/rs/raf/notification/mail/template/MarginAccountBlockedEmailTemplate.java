package rs.raf.notification.mail.template;

import org.springframework.stereotype.Component;

/**
 * Email sablon za blokadu margin racuna (margin call). trading-service
 * publish-uje {@code MARGIN_ACCOUNT_BLOCKED} kad dnevna provera maintenance
 * margine blokira racun; ovaj sablon gradi obavestenje vlasniku.
 */
@Component
public class MarginAccountBlockedEmailTemplate {

    public String buildSubject() {
        return "Margin racun blokiran - Banka 2";
    }

    public String buildBody(String maintenanceMargin, String initialMargin, String deficit) {
        return """
                <!DOCTYPE html>
                <html lang="sr">
                <head>
                    <meta charset="UTF-8">
                    <title>Margin racun blokiran</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f8fafc;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                <table role="presentation" cellpadding="0" cellspacing="0" width="100%%" style="padding:32px 0;">
                    <tr>
                        <td align="center">
                            <table role="presentation" cellpadding="0" cellspacing="0" width="100%%" style="max-width:520px;background-color:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 20px 50px rgba(99,102,241,0.18);border:1px solid #e5e7eb;">
                                <tr>
                                    <td style="background:linear-gradient(135deg,#6366f1,#7c3aed);padding:28px 24px;text-align:center;">
                                        <p style="margin:0 0 4px 0;font-size:13px;font-weight:500;color:rgba(255,255,255,0.7);letter-spacing:0.08em;text-transform:uppercase;">Banka 2</p>
                                        <h1 style="margin:0;font-size:22px;font-weight:700;color:#ffffff;letter-spacing:0.01em;">Margin racun blokiran</h1>
                                    </td>
                                </tr>
                                <tr>
                                    <td style="padding:32px 28px;text-align:center;">
                                        <p style="margin:0 0 20px 0;font-size:14px;color:#4b5563;line-height:1.6;">
                                            Vaš margin racun je blokiran jer je vrednost margine pala ispod
                                            zahtevanog nivoa odrzavanja (maintenance margin). Da biste ponovo
                                            aktivirali racun, uplatite dovoljno sredstava na margin racun.
                                        </p>
                                        <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 auto 24px auto;background-color:#eef2ff;border-radius:12px;border:1px solid #c7d2fe;padding:0;overflow:hidden;width:100%%;">
                                            %s
                                            %s
                                            %s
                                        </table>
                                        <p style="margin:0;font-size:12px;color:#9ca3af;">
                                            Za vise informacija kontaktirajte vaseg agenta ili podrsku Banke 2.
                                        </p>
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
                """.formatted(
                        row("Pocetna margina", initialMargin),
                        row("Maintenance margina", maintenanceMargin),
                        row("Manjak", deficit));
    }

    private String row(String label, String value) {
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
                """.formatted(EmailHtml.escape(label),
                        EmailHtml.escape(value != null ? value : "-"));
    }
}
