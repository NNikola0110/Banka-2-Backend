package rs.raf.notification.mail.template;

import org.springframework.stereotype.Component;

@Component
public class ActivationConfirmedEmailTemplate {

    public String buildSubject() {
        return "Vaš Banka 2 nalog je aktiviran";
    }

    public String buildBody(String firstName) {
        String greeting = (firstName != null && !firstName.isBlank()) ? firstName : "Poštovani";
        return """
                <!DOCTYPE html>
                <html lang="sr">
                <head>
                    <meta charset="UTF-8">
                    <title>Nalog aktiviran</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f8fafc;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                <table role="presentation" cellpadding="0" cellspacing="0" width="100%%" style="padding:32px 0;">
                    <tr>
                        <td align="center">
                            <table role="presentation" cellpadding="0" cellspacing="0" width="100%%" style="max-width:520px;background-color:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 20px 50px rgba(99,102,241,0.18);border:1px solid #e5e7eb;">
                                <tr>
                                    <td style="background:linear-gradient(135deg,#6366f1,#7c3aed);padding:28px 24px;text-align:center;">
                                        <p style="margin:0 0 4px 0;font-size:13px;font-weight:500;color:rgba(255,255,255,0.7);letter-spacing:0.08em;text-transform:uppercase;">Banka 2</p>
                                        <h1 style="margin:0;font-size:22px;font-weight:700;color:#ffffff;letter-spacing:0.01em;">Nalog aktiviran</h1>
                                    </td>
                                </tr>
                                <tr>
                                    <td style="padding:32px 28px;text-align:center;">
                                        <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 auto 20px auto;">
                                            <tr>
                                                <td style="width:56px;height:56px;border-radius:50%%;background:linear-gradient(135deg,#22c55e,#16a34a);text-align:center;vertical-align:middle;font-size:28px;color:#ffffff;">
                                                    &#10003;
                                                </td>
                                            </tr>
                                        </table>
                                        <p style="margin:0 0 16px 0;font-size:15px;color:#374151;font-weight:600;">Zdravo %s,</p>
                                        <p style="margin:0 0 12px 0;font-size:14px;color:#4b5563;line-height:1.6;">
                                            Vaš Banka 2 nalog je uspešno aktiviran.
                                        </p>
                                        <p style="margin:0;font-size:13px;color:#6b7280;line-height:1.5;">
                                            Sada se možete prijaviti sa vašim emailom i lozinkom.
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
                """.formatted(greeting);
    }
}
