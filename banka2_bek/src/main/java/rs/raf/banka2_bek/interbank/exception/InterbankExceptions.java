package rs.raf.banka2_bek.interbank.exception;

/*
================================================================================
 EXCEPTION HIJERARHIJA ZA INTER-BANK SLOJ (PROTOKOL §2.9-§2.11)
--------------------------------------------------------------------------------
 InterbankException (base RuntimeException)
   ├─ InterbankCommunicationException  — HTTP/mrezne greske (timeout, 5xx,
   │                                     unknown bank, network failure)
   ├─ InterbankAuthException           — §2.10 los X-Api-Key (401 napred-nazad)
   ├─ InterbankProtocolException       — validacija (nevalidan envelope,
   │                                     unknown messageType, unbalanced tx,
   │                                     malformed JSON)
   ├─ InterbankIdempotencyException    — §2.2 konflikti pri trajnom belezenju
   │                                     idempotence kljuceva
   ├─ InterbankTransactionStuckException — retry scheduler odustao posle MAX_RETRY
   │  ── OTC negotiation/exercise (NE prolaze kroz inbound advice; vidi nize) ──
   ├─ InterbankNegotiationConflictException — §3.3 turn-violation / zatvoren
   │                                     pregovor (409 Conflict)
   ├─ InterbankUserNotFoundException   — §3.7 GET /user/{rn}/{id} nepostojeci (404)
   ├─ InterbankNegotiationNotFoundException — §3.3-§3.6 nepostojeci pregovor (404)
   └─ InterbankExerciseConflictException — §2.7.2 exercise nad ne-ACTIVE / posle
                                          settlement / pogresna strana (409 Conflict)

 HTTP MAPING — R1-684: pre-fix doc je tvrdio 502/500 mappinge koje INBOUND advice
 (InterbankExceptionHandler, assignableTypes=InterbankInboundController) NE radi.
 Realno stanje inbound advice-a (@RestControllerAdvice za inbound 2PC kontroler):
   InterbankAuthException              -> 401 Unauthorized (inbound los X-Api-Key)
   InterbankProtocolException          -> 400 Bad Request   (malformed/unbalanced envelope)
   JsonProcessingException             -> 400 Bad Request   (malformed JSON)
   SVE OSTALO (Communication/Idempotency/Stuck/...) -> 500 Internal Server Error
                                          (handleGeneral catch-all)

 NAPOMENA 1: cetiri OTC negotiation/exercise tipa (NegotiationConflict 409,
 UserNotFound 404, NegotiationNotFound 404, ExerciseConflict 409) se NE mapiraju
 kroz inbound 2PC advice — njih hvataju posebni OTC advice-i (OtcNegotiationExceptionHandler
 za negotiation kontroler, InterbankOtcWrapperExceptionHandler za wrapper kontroler),
 pa NE padaju u gornji 500 catch-all.

 NAPOMENA 2: InterbankCommunicationException je OUTBOUND pojam (mi zovemo partnera i
 dobijemo timeout/5xx) — ne baca se tokom inbound obrade pa ga inbound advice i ne
 mapira posebno; outbound pozivi ga hvataju lokalno (vidi InterbankRetryScheduler /
 InterbankClient). InterbankIdempotency/Stuck na inbound putu padaju na 500 (retry/
 manuelna intervencija ih kasnije razresava).
================================================================================
*/
public final class InterbankExceptions {

    private InterbankExceptions() {
    }

    public static class InterbankException extends RuntimeException {
        public InterbankException(String message) {
            super(message);
        }

        public InterbankException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class InterbankCommunicationException extends InterbankException {
        public InterbankCommunicationException(String message) {
            super(message);
        }

        public InterbankCommunicationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * §2.10 — los ili nedostaje X-Api-Key.
     */
    public static class InterbankAuthException extends InterbankException {
        public InterbankAuthException(String message) {
            super(message);
        }
    }

    public static class InterbankProtocolException extends InterbankException {
        public InterbankProtocolException(String message) {
            super(message);
        }
    }

    /**
     * §3.3 — PUT /negotiations/{rn}/{id} pozvan kad nije turn pozivaoca, ILI je
     * pregovor vec zatvoren. Spec eksplicitno trazi HTTP 409 Conflict (ne 400),
     * jer to nije malformed input — to je validan zahtev koji konflitkuje sa
     * trenutnim stanjem resursa.
     */
    public static class InterbankNegotiationConflictException extends InterbankException {
        public InterbankNegotiationConflictException(String message) {
            super(message);
        }
    }

    /**
     * §3.7 — GET /user/{rn}/{id} za nepostojeci ID. Spec trazi HTTP 404 Not Found
     * (ne 400). Razlikujemo od ostalih protocol greska.
     */
    public static class InterbankUserNotFoundException extends InterbankException {
        public InterbankUserNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * §3.3-§3.6 — PUT/GET/DELETE /negotiations/{rn}/{id} za nepostojeci pregovor.
     * Spec trazi HTTP 404 Not Found. Razlikujemo od 409 Conflict (turn violation /
     * zatvoreni pregovor) i 400 Bad Request (malformed input).
     */
    public static class InterbankNegotiationNotFoundException extends InterbankException {
        public InterbankNegotiationNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * §2.7.2 — exercise opcije pozvan na ugovor koji nije ACTIVE, ili posle
     * settlementDate-a, ili od strane koja nije kupac, ili na ugovoru koji ne
     * pripada pozivacu. HTTP 409 Conflict (ne 400, jer su payload-i ispravni —
     * stanje resursa konfliktuje).
     */
    public static class InterbankExerciseConflictException extends InterbankException {
        public InterbankExerciseConflictException(String message) {
            super(message);
        }
    }

    /**
     * §2.2 — duplikat pri belezenju idempotence kljuca ili stale cached response.
     */
    public static class InterbankIdempotencyException extends InterbankException {
        public InterbankIdempotencyException(String message) {
            super(message);
        }
    }

    public static class InterbankTransactionStuckException extends InterbankException {
        public InterbankTransactionStuckException(String message) {
            super(message);
        }
    }
}
