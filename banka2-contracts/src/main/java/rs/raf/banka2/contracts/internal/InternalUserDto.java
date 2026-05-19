package rs.raf.banka2.contracts.internal;

/**
 * Identitet korisnika za trading-service.
 * trading-service JWT nosi samo email; ovaj DTO razresava numericki id + rolu.
 */
public record InternalUserDto(Long userId, String userRole, String email,
                              String firstName, String lastName, boolean active) {
}
