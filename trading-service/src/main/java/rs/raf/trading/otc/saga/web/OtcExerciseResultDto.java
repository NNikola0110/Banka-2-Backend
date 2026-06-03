package rs.raf.trading.otc.saga.web;

/**
 * Odgovor na {@code POST /otc/contracts/{id}/exercise} pod Model-B SAGA
 * orkestratorom (SAGA_test.pdf autoritativno).
 *
 * <p>SAGA se izvrsava sinhrono unutar zahteva; odgovor nosi <b>terminalni</b>
 * ishod (HTTP 200 i za COMPLETED i za COMPENSATED — klijent cita
 * {@code sagaStatus}/{@code status} da sazna ishod, ili poll-uje
 * {@code GET /otc/saga/{sagaId}} za pun log).
 *
 * <p>Polja:
 * <ul>
 *   <li>{@code sagaId} — jedinstveni handle za polling preko {@code GET /otc/saga/{id}}</li>
 *   <li>{@code sagaStatus} — terminalni {@code SagaStatus} ime (COMPLETED / COMPENSATED)</li>
 *   <li>{@code currentStep} — ordinal poslednje pokusane forward faze (1..5)</li>
 *   <li>{@code id} — id ugovora (paritet sa starim {@code OtcContractDto} oblikom +
 *       Uputstvo system-test mock {@code {id, status}})</li>
 *   <li>{@code status} — trenutni status ugovora (EXERCISED na uspeh / ACTIVE na rollback)</li>
 * </ul>
 */
public record OtcExerciseResultDto(String sagaId, String sagaStatus, int currentStep,
                                   Long id, String status) {
}
