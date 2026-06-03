package rs.raf.banka2_bek.internalapi.service;

/**
 * R1 402 — nevalidna {@code {userRole}} putanja na internom API-ju je KLIJENTSKA
 * greska (400 BAD_REQUEST), ne 404 NOT_FOUND. Nasledjuje {@link IllegalArgumentException}
 * (ocuvana postojeca semantika), a {@code InternalApiExceptionHandler} ima
 * specificniji handler koji je mapira u 400 (umesto generic IAE→404 za
 * "resurs ne postoji"). "Client/Employee not found" i dalje ostaje 404.
 */
public class UnknownUserRoleException extends IllegalArgumentException {

    public UnknownUserRoleException(String userRole) {
        super("Unknown user role: " + userRole);
    }
}
