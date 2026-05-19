package rs.raf.banka2.contracts.internal;

/**
 * Zahtev za bulk prebacivanje vlasnistva nad fondovima — svi fondovi kojima
 * upravlja {@code oldManagerEmployeeId} dobijaju {@code newManagerEmployeeId}
 * kao novog menadzera.
 *
 * <p>Faza 2f: banka-core {@code employee} paket (kada admin oduzme SUPERVISOR
 * permisiju supervizoru) salje ovaj zahtev na trading-service interni endpoint
 * {@code POST /internal/funds/reassign-manager} jer {@code investmentfund} tabele
 * posle cutover-a zive samo u trading_db.
 */
public record ReassignFundManagerRequest(Long oldManagerEmployeeId, Long newManagerEmployeeId) {
}
