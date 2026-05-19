package rs.raf.notification.mail.template;

import org.springframework.stereotype.Component;

/**
 * [B1] Genericki branded email sablon za in-app notifikacije.
 *
 * <p>Koristi se kada {@code NotificationPublisher} u banka-core publishes
 * {@code IN_APP_GENERIC} poruku; {@code NotificationConsumer} je konzumira
 * i delegira na ovaj sablon. Subjekat je {@code title} notifikacije, a
 * sadrzaj je njen {@code body}.
 *
 * <p>[B4 — Petar Poznanovic] Kad se dodaju tipizovani email sabloni po vrstama
 * notifikacija (PAYMENT, ORDER_*, OTC_*...), consumer-ov {@code case IN_APP_GENERIC}
 * treba da preusmeri na odgovarajuci specijalizovani metod. Ovaj genericki
 * sablon ostaje kao fallback za neprepoznate vrste.
 */
@Component
public class InAppGenericEmailTemplate {

    /**
     * Gradi HTML telo branded email-a.
     *
     * @param firstName ime primaoca za pozdrav; ako je {@code null} ili prazno,
     *                  koristi se neutralni pozdrav "korisnice"
     * @param title     naslov notifikacije — postaje naslov email-a i H1 u headeru
     * @param body      sadrzaj notifikacije — prikazuje se u telu email-a
     * @return kompletan HTML string spreman za slanje
     */
    public String buildBody(String firstName, String title, String body) {
        String greeting = (firstName != null && !firstName.isBlank())
                ? "Poštovani " + firstName + ","
                : "Poštovana korisnice,";
        return """
                <!DOCTYPE html>
                <html lang="sr">
                <head>
                    <meta charset="UTF-8">
                    <title>%s</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f8fafc;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                <table role="presentation" cellpadding="0" cellspacing="0" width="100%%" style="padding:32px 0;">
                    <tr>
                        <td align="center">
                            <table role="presentation" cellpadding="0" cellspacing="0" width="100%%" style="max-width:520px;background-color:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 20px 50px rgba(99,102,241,0.18);border:1px solid #e5e7eb;">
                                <tr>
                                    <td style="background:linear-gradient(135deg,#6366f1,#7c3aed);padding:28px 24px;text-align:center;">
                                        <p style="margin:0 0 4px 0;font-size:13px;font-weight:500;color:rgba(255,255,255,0.7);letter-spacing:0.08em;text-transform:uppercase;">Banka 2</p>
                                        <h1 style="margin:0;font-size:22px;font-weight:700;color:#ffffff;letter-spacing:0.01em;">%s</h1>
                                    </td>
                                </tr>
                                <tr>
                                    <td style="padding:32px 28px;">
                                        <p style="margin:0 0 12px 0;font-size:14px;color:#4b5563;font-weight:600;">%s</p>
                                        <p style="margin:0;font-size:14px;color:#4b5563;line-height:1.6;">%s</p>
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
                """.formatted(title, title, greeting, body);
    }
}
