package rs.raf.notification.mail.template;

import org.springframework.stereotype.Component;

@Component
public class ActivationEmailTemplate {

    public String buildSubject() {
        return "Aktivirajte vaš Banka 2 nalog";
    }

    public String buildBody(String activationLink, String firstName) {
        String greeting = (firstName != null && !firstName.isBlank()) ? firstName : "there";
        return """
                <!DOCTYPE html>
                <html lang="sr">
                <head>
                    <meta charset="UTF-8">
                    <title>Aktivacija naloga</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f8fafc;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                <table role="presentation" cellpadding="0" cellspacing="0" width="100%%" style="padding:32px 0;">
                    <tr>
                        <td align="center">
                            <table role="presentation" cellpadding="0" cellspacing="0" width="100%%" style="max-width:520px;background-color:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 20px 50px rgba(99,102,241,0.18);border:1px solid #e5e7eb;">
                                <tr>
                                    <td style="background:linear-gradient(135deg,#6366f1,#7c3aed);padding:28px 24px;text-align:center;">
                                        <p style="margin:0 0 4px 0;font-size:13px;font-weight:500;color:rgba(255,255,255,0.7);letter-spacing:0.08em;text-transform:uppercase;">Banka 2</p>
                                        <h1 style="margin:0;font-size:22px;font-weight:700;color:#ffffff;letter-spacing:0.01em;">Aktivacija naloga</h1>
                                    </td>
                                </tr>
                                <tr>
                                    <td style="padding:32px 28px;text-align:center;">
                                        <p style="margin:0 0 16px 0;font-size:15px;color:#374151;font-weight:600;">Zdravo %s,</p>
                                        <p style="margin:0 0 16px 0;font-size:14px;color:#4b5563;line-height:1.6;">
                                            Vaš nalog zaposlenog u Banka 2 je kreiran. Kliknite na dugme ispod kako biste ga aktivirali i postavili svoju lozinku.
                                        </p>
                                        <p style="margin:0 0 24px 0;font-size:13px;color:#6b7280;line-height:1.5;">
                                            Link za aktivaciju ističe za 24 sata i može se koristiti samo jednom.
                                        </p>
                                        <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 auto 28px auto;">
                                            <tr>
                                                <td style="border-radius:10px;background:linear-gradient(135deg,#6366f1,#7c3aed);">
                                                    <a href="%s" style="display:inline-block;padding:14px 36px;color:#ffffff;text-decoration:none;font-size:14px;font-weight:600;letter-spacing:0.03em;">
                                                        Aktivirajte nalog
                                                    </a>
                                                </td>
                                            </tr>
                                        </table>
                                        <p style="margin:0 0 10px 0;font-size:12px;color:#9ca3af;">
                                            Ako dugme ne radi, kopirajte i nalepite ovaj link u pretraživač:
                                        </p>
                                        <p style="margin:0 0 20px 0;font-size:12px;color:#6366f1;word-break:break-all;">
                                            %s
                                        </p>
                                        <p style="margin:0;font-size:12px;color:#9ca3af;">
                                            Ako niste očekivali ovaj email, molimo kontaktirajte vašeg administratora.
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
                """.formatted(greeting, activationLink, activationLink);
    }
}
