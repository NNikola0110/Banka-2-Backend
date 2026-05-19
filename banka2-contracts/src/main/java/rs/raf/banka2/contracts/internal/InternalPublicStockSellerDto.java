package rs.raf.banka2.contracts.internal;

/**
 * Jedna javno-vidljiva pozicija u portfoliju — prodavac koji nudi hartije za
 * inter-bank OTC trgovinu (protokol §3.1 public-stock).
 *
 * <p>banka-core {@code interbank} paket cita sve pozicije sa {@code publicQuantity > 0}
 * preko {@code GET /internal/portfolio/public-stock} i grupise ih po ticker-u,
 * gradeci {@code PublicStock} protokol odgovor (sa prefiks-kodiranim seller id-em).
 */
public record InternalPublicStockSellerDto(Long userId, String userRole, String ticker,
                                           int publicQuantity) {
}
