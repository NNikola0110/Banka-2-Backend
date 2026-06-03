package rs.raf.trading.recurringorder.service;

/**
 * R1-241 / R3-1582 — signalizira PROLAZNU infra-gresku pri izvrsavanju trajnog
 * naloga (banka-core nedostupan, DB/optimistic-lock, konekcija). Kada
 * {@code RecurringOrderService.executeOne} klasifikuje gresku kao prolaznu,
 * propagira je ovim tipom (umesto da tiho napreduje {@code nextRun}) → nalog
 * ostaje dospeo i pokusava se ponovo sledeci scheduler ciklus. Scheduler loop
 * je hvata i loguje per-nalog, bez prekida ostatka batch-a.
 */
public class RecurringOrderExecutionException extends RuntimeException {
    public RecurringOrderExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
