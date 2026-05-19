package rs.raf.banka2.contracts.internal;

/**
 * Odgovor na bulk prebacivanje vlasnistva nad fondovima — broj fondova kojima
 * je promenjen menadzer ({@code reassignedCount}).
 */
public record ReassignFundManagerResponse(int reassignedCount) {
}
