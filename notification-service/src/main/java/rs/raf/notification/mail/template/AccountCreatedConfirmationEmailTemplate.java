package rs.raf.notification.mail.template;

import org.springframework.stereotype.Component;

@Component
public class AccountCreatedConfirmationEmailTemplate {

    public String buildSubject() {
        return "Vaš novi račun u Banka 2 je kreiran";
    }

    public String buildBody(String firstName, String accountNumber, String accountType) {
        // [P2-input-validation-1 / R1 385] escape user-controlled vrednosti (ime,
        // tip racuna, broj racuna) pre interpolacije u HTML telo — sprecava
        // HTML/<script>/<img> injection iz firstName/accountType/accountNumber.
        String greeting = EmailHtml.escape(
                (firstName != null && !firstName.isBlank()) ? firstName : "Poštovani");
        String safeAccountType = EmailHtml.escape(accountType);
        String safeAccountNumber = EmailHtml.escape(accountNumber);
        return """
                <!DOCTYPE html>
                <html lang="sr">
                <head>
                    <meta charset="UTF-8">
                    <title>Račun kreiran</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f8fafc;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                <table role="presentation" cellpadding="0" cellspacing="0" width="100%%" style="padding:32px 0;">
                    <tr>
                        <td align="center">
                            <table role="presentation" cellpadding="0" cellspacing="0" width="100%%" style="max-width:520px;background-color:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 20px 50px rgba(99,102,241,0.18);border:1px solid #e5e7eb;">
                                <tr>
                                    <td style="background:linear-gradient(135deg,#6366f1,#7c3aed);padding:28px 24px;text-align:center;">
                                        <p style="margin:0 0 4px 0;font-size:13px;font-weight:500;color:rgba(255,255,255,0.7);letter-spacing:0.08em;text-transform:uppercase;">Banka 2</p>
                                        <h1 style="margin:0;font-size:22px;font-weight:700;color:#ffffff;letter-spacing:0.01em;">Novi račun kreiran</h1>
                                    </td>
                                </tr>
                                <tr>
                                    <td style="padding:32px 28px;text-align:center;">
                                        <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 auto 20px auto;">
                                            <tr>
                                                <td style="width:56px;height:56px;border-radius:50%%;background:linear-gradient(135deg,#6366f1,#7c3aed);text-align:center;vertical-align:middle;font-size:28px;color:#ffffff;">
                                                    &#9745;
                                                </td>
                                            </tr>
                                        </table>
                                        <p style="margin:0 0 16px 0;font-size:15px;color:#374151;font-weight:600;">Zdravo %s,</p>
                                        <p style="margin:0 0 20px 0;font-size:14px;color:#4b5563;line-height:1.6;">
                                            Vaš novi bankovni račun je uspešno otvoren i spreman za korišćenje.
                                        </p>
                                        <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 auto 24px auto;background-color:#eef2ff;border-radius:12px;border:1px solid #c7d2fe;padding:0;overflow:hidden;width:100%%;">
                                            <tr>
                                                <td style="padding:14px 20px;border-bottom:1px solid #c7d2fe;">
                                                    <table role="presentation" cellpadding="0" cellspacing="0" width="100%%">
                                                        <tr>
                                                            <td style="font-size:12px;color:#6b7280;text-align:left;">Tip računa</td>
                                                            <td style="font-size:13px;font-weight:600;color:#4338ca;text-align:right;">%s</td>
                                                        </tr>
                                                    </table>
                                                </td>
                                            </tr>
                                            <tr>
                                                <td style="padding:14px 20px;">
                                                    <table role="presentation" cellpadding="0" cellspacing="0" width="100%%">
                                                        <tr>
                                                            <td style="font-size:12px;color:#6b7280;text-align:left;">Broj računa</td>
                                                            <td style="font-size:13px;font-weight:600;color:#4338ca;text-align:right;letter-spacing:0.05em;">%s</td>
                                                        </tr>
                                                    </table>
                                                </td>
                                            </tr>
                                        </table>
                                        <p style="margin:0;font-size:12px;color:#9ca3af;">
                                            Molimo sačuvajte broj računa na sigurnom mestu. Ako niste očekivali ovaj email, kontaktirajte podršku.
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
                """.formatted(greeting, safeAccountType, safeAccountNumber);
    }
}
