package rs.raf.trading.otc.saga.support;

import org.springframework.web.client.ResourceAccessException;
import rs.raf.banka2.contracts.internal.CommitFundsRequest;
import rs.raf.banka2.contracts.internal.CommitFundsResponse;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chaos dvojnik banka-core klijenta za <b>mrezni</b> (transport-level) fault —
 * verno simulira Toxiproxy {@code down} (connection refused) ili {@code latency >
 * read-timeout} (socket read-timeout) na konkretnom banka-core pozivu usred SAGA-e
 * (SG-09a / SG-10).
 *
 * <p><b>Zasto zaseban dvojnik (a ne {@link FakeBankaCoreClient#failNextCredit()}):</b>
 * postojeci {@code failNextCredit} baca {@link rs.raf.trading.client.BankaCoreClientException}
 * sa HTTP statusom 503 — to je <i>aplikacioni</i> (HTTP-level) odgovor banke. Mrezni
 * chaos (Toxiproxy {@code down}/{@code latency}, {@code docker pause backend}) se na
 * nivou {@code RestClient}-a manifestuje kao {@link ResourceAccessException} (omota
 * {@link java.net.ConnectException} / {@link SocketTimeoutException}) — NIKAD ne stigne
 * HTTP odgovor, pa nema status koda. Taj transport put nijedan postojeci automatski
 * test ne pokriva; ovaj dvojnik ga zatvara.
 *
 * <p>Orkestrator hvata {@code RuntimeException} (a {@link ResourceAccessException}
 * jeste {@code RuntimeException}) u {@code runForward} → kompenzacija. Time se
 * dokazuje da transport-fault put vodi u korektnu kompenzaciju + ocuvanje invarijanti,
 * isto kao i pravi mrezni chaos iz runbook-a (docs/chaos-testing.md).
 *
 * <p>Nasledjuje svu konzervaciono-korektnu in-memory semantiku
 * {@link FakeBankaCoreClient} (balance/reserved/totalMoney), pa I1/I2 asercije rade
 * identicno. Fault se "naoruzava" pre exercise-a ({@link #armReserveFundsTransportFail()}
 * / {@link #armCommitFundsTransportFail()}) i okida JEDNOM (one-shot), pa
 * kompenzator (npr. C1 {@code releaseFunds}) NE puca ponovo — tacno kao kad se
 * Toxiproxy toxic ukloni pre kompenzacije, ili kad se banka-core odpauzira.
 */
public class NetworkChaosBankaCoreClient extends FakeBankaCoreClient {

    /** &gt;0 → sledeci {@link #reserveFunds} baca transport izuzetak (one-shot). */
    private final AtomicInteger failNextReserve = new AtomicInteger(0);
    /** &gt;0 → sledeci {@link #commitFunds} baca transport izuzetak (one-shot). */
    private final AtomicInteger failNextCommit = new AtomicInteger(0);

    /**
     * SG-09a: sledeci {@code reserveFunds} (F1 nova rezervacija) puca kao da je
     * banka-core link oboren (connection refused) — pad PRE bilo kog bocnog efekta.
     */
    public void armReserveFundsTransportFail() {
        failNextReserve.set(1);
    }

    /**
     * SG-10: sledeci {@code commitFunds} (F3 prva novcana noga) puca kao read-timeout
     * (latency &gt; read-timeout) — pad POSLE uspele F1 rezervacije, pre prenosa novca.
     */
    public void armCommitFundsTransportFail() {
        failNextCommit.set(1);
    }

    @Override
    public ReserveFundsResponse reserveFunds(String idempotencyKey, ReserveFundsRequest req) {
        if (failNextReserve.get() > 0) {
            failNextReserve.decrementAndGet();
            // Toxiproxy `down` / `docker pause backend` → konekcija odbijena.
            throw new ResourceAccessException(
                    "I/O error on POST /internal/funds/reserve: Connection refused (chaos: banka-core down)",
                    new ConnectExceptionStub("Connection refused (SG-09a forsiran transport fault)"));
        }
        return super.reserveFunds(idempotencyKey, req);
    }

    @Override
    public CommitFundsResponse commitFunds(String reservationId, String idempotencyKey,
                                           CommitFundsRequest req) {
        if (failNextCommit.get() > 0) {
            failNextCommit.decrementAndGet();
            // Toxiproxy `latency > read-timeout` → socket read-timeout (nema HTTP odgovora).
            throw new ResourceAccessException(
                    "I/O error on POST /internal/funds/.../commit: Read timed out (chaos: banka-core latency)",
                    new SocketTimeoutException("Read timed out (SG-10 forsiran transport fault)"));
        }
        return super.commitFunds(reservationId, idempotencyKey, req);
    }

    /** Minimalni {@link IOException} stand-in za connection-refused uzrok (java.net.ConnectException je final-friendly konstruktor). */
    private static final class ConnectExceptionStub extends IOException {
        ConnectExceptionStub(String message) {
            super(message);
        }
    }
}
