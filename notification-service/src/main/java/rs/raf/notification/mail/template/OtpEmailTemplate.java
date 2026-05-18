package rs.raf.notification.mail.template;

import org.springframework.stereotype.Component;

@Component
public class OtpEmailTemplate {

    public String buildSubject() {
        return "Vaš verifikacioni kod - Banka 2";
    }

    public String buildBody(String code, int expiryMinutes) {
        return """
                <!DOCTYPE html>
                <html lang="sr">
                <head>
                    <meta charset="UTF-8">
                    <title>Verifikacioni kod</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f8fafc;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                <table role="presentation" cellpadding="0" cellspacing="0" width="100%%" style="padding:32px 0;">
                    <tr>
                        <td align="center">
                            <table role="presentation" cellpadding="0" cellspacing="0" width="100%%" style="max-width:520px;background-color:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 20px 50px rgba(99,102,241,0.18);border:1px solid #e5e7eb;">
                                <tr>
                                    <td style="background:linear-gradient(135deg,#6366f1,#7c3aed);padding:28px 24px;text-align:center;">
                                        <p style="margin:0 0 4px 0;font-size:13px;font-weight:500;color:rgba(255,255,255,0.7);letter-spacing:0.08em;text-transform:uppercase;">Banka 2</p>
                                        <h1 style="margin:0;font-size:22px;font-weight:700;color:#ffffff;letter-spacing:0.01em;">Verifikacioni kod</h1>
                                    </td>
                                </tr>
                                <tr>
                                    <td style="padding:32px 28px;text-align:center;">
                                        <p style="margin:0 0 16px 0;font-size:14px;color:#4b5563;line-height:1.6;">
                                            Vaš verifikacioni kod za potvrdu transakcije:
                                        </p>
                                        <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 auto 24px auto;">
                                            <tr>
                                                <td style="border-radius:12px;background:linear-gradient(135deg,#6366f1,#7c3aed);padding:16px 48px;">
                                                    <p style="margin:0;font-size:32px;font-weight:700;color:#ffffff;letter-spacing:0.3em;font-family:monospace;">
                                                        %s
                                                    </p>
                                                </td>
                                            </tr>
                                        </table>
                                        <p style="margin:0 0 8px 0;font-size:13px;color:#6b7280;line-height:1.5;">
                                            Kod istice za <strong>%d minuta</strong>.
                                        </p>
                                        <p style="margin:0 0 8px 0;font-size:13px;color:#6b7280;line-height:1.5;">
                                            Imate 3 pokusaja za unos koda.
                                        </p>
                                        <p style="margin:16px 0 0 0;font-size:12px;color:#9ca3af;">
                                            Ako niste pokrenuli ovu transakciju, molimo kontaktirajte vaseg administratora.
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
                """.formatted(code, expiryMinutes);
    }
}
