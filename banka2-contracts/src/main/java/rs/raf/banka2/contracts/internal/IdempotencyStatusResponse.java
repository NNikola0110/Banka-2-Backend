package rs.raf.banka2.contracts.internal;

/**
 * <b>N4 (P0-T2):</b> autoritativan read-only odgovor da li je dati idempotency
 * kljuc VEC KONZUMIRAN u banka-core internom funds API-ju ({@code reserve/commit/
 * release/transfer/credit/debit/tax-collect} dedup store, {@code internal_requests}
 * tabela). {@code consumed=true} znaci da je operacija sa tim kljucem ZAISTA
 * izvrsena (i njen rezultat kesiran); {@code false} da nije ni pokrenuta.
 *
 * <p>Koristi ga OTC SAGA recovery ({@code OtcExerciseSagaOrchestrator.c3RefundBuyer})
 * da AUTORITATIVNO utvrdi da li je F3 {@code creditFunds} (isplata prodavcu) izvrsen
 * pre pada koordinatora — umesto da pogadja po write-ahead flag-ovima. Tako C3 bira:
 * {@code consumed=true} → pun reverzni transfer (ponisti isplatu); {@code consumed=false}
 * → commit-only refund kupcu (prodavac NIKAD nije primio novac, ne sme se debitovati).
 *
 * <p>Striktno INTERNI API (trading↔banka-core, X-Internal-Key), nije inter-bank wire.
 */
public record IdempotencyStatusResponse(String idempotencyKey, boolean consumed) {
}
