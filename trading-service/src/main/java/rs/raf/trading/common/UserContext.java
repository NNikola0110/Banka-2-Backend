package rs.raf.trading.common;

/**
 * Resolve-ovani identitet trenutnog zahteva: internal ID (iz clients ili
 * employees tabele) + uloga kao string ("CLIENT" ili "EMPLOYEE").
 *
 * Ranije je bio duplikovan kao privatni record u {@code OrderServiceImpl} i
 * {@code OtcService}; sada je jedinstven.
 */
public record UserContext(Long userId, String userRole) {
    public boolean isClient() {
        return UserRole.CLIENT.equals(userRole);
    }

    public boolean isEmployee() {
        return UserRole.EMPLOYEE.equals(userRole);
    }
}
