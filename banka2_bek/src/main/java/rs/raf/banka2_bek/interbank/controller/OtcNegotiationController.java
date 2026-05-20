package rs.raf.banka2_bek.interbank.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import rs.raf.banka2_bek.interbank.protocol.ForeignBankId;
import rs.raf.banka2_bek.interbank.protocol.OtcNegotiation;
import rs.raf.banka2_bek.interbank.protocol.OtcOffer;
import rs.raf.banka2_bek.interbank.protocol.PublicStock;
import rs.raf.banka2_bek.interbank.protocol.UserInformation;
import rs.raf.banka2_bek.interbank.service.OtcNegotiationService;

import java.util.List;

/**
 * Inbound endpoint-i za OTC pregovor protokol (§3.1–§3.7).
 * Sve rute autentifikuje {@code InterbankAuthFilter} kroz {@code X-Api-Key} header
 * (§2.10). Filter resolvuje partner banku i odbacuje neautorizovane zahteve sa 401.
 */
@RestController
@RequiredArgsConstructor
public class OtcNegotiationController {

    private final OtcNegotiationService negotiationService;

    /** §3.1 — javne akcije za OTC discovery iz druge banke. */
    @GetMapping("/public-stock")
    public ResponseEntity<List<PublicStock>> listPublicStocks() {
        return ResponseEntity.ok(negotiationService.serveLocalPublicStocks());
    }

    /** §3.2 — partner banka kreira pregovor; mi smo seller (autoritativni vlasnik). */
    @PostMapping("/negotiations")
    public ResponseEntity<ForeignBankId> createNegotiation(@RequestBody OtcOffer offer) {
        // T2-D fix (Tim 1 cross-bank Stage C, 2026-05-20): lastModifiedBy.routingNumber
        // mora odgovarati X-Api-Key sender-u. Bez ovog, partner banka moze "podmetnuti"
        // lastModifier kao iz nase strane (npr. {222, C-1}) i tako falsifikovati ko je
        // izmenio pregovor — sledeci PUT bi prosao turn check kao da je nas red.
        requireSenderRoutingMatchesLastModifier(offer);
        ForeignBankId id = negotiationService.acceptCreatedNegotiation(offer);
        return ResponseEntity.ok(id);
    }

    /** §3.4 — citaj trenutno stanje pregovora po (routingNumber, id). */
    @GetMapping("/negotiations/{routingNumber}/{id}")
    public ResponseEntity<OtcNegotiation> readNegotiation(
            @PathVariable int routingNumber,
            @PathVariable String id) {
        return ResponseEntity.ok(negotiationService.getNegotiation(new ForeignBankId(routingNumber, id)));
    }

    /** §3.3 — counter-offer od strane druge banke; turn-rule provera u service-u. */
    @PutMapping("/negotiations/{routingNumber}/{id}")
    public ResponseEntity<Void> postCounterOffer(
            @PathVariable int routingNumber,
            @PathVariable String id,
            @RequestBody OtcOffer offer) {
        // T2-D fix — vidi createNegotiation komentar.
        requireSenderRoutingMatchesLastModifier(offer);
        negotiationService.receiveCounterOffer(new ForeignBankId(routingNumber, id), offer);
        return ResponseEntity.noContent().build();
    }

    /**
     * T2-D helper: izvuci sender routing iz {@code InterbankAuthFilter}-ovog
     * {@code SecurityContextHolder} i odbij zahtev sa 400 ako
     * {@code offer.lastModifiedBy.routingNumber} ne odgovara senderu. Spec §3.2/§3.3
     * eksplicitno trazi ovu proveru — bez nje partner banka moze falsifikovati
     * lastModifier i razbiti turn check.
     */
    private void requireSenderRoutingMatchesLastModifier(OtcOffer offer) {
        if (offer == null || offer.lastModifiedBy() == null) {
            throw new IllegalArgumentException("offer.lastModifiedBy je obavezan");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Integer senderRouting)) {
            throw new IllegalStateException(
                    "Sender routing nedostaje — InterbankAuthFilter preduslov");
        }
        if (offer.lastModifiedBy().routingNumber() != senderRouting) {
            throw new IllegalArgumentException(
                    "lastModifiedBy.routingNumber (" + offer.lastModifiedBy().routingNumber()
                            + ") mora odgovarati X-Api-Key sender-u (" + senderRouting + ")");
        }
    }

    /** §3.5 — bilo koja strana zatvara pregovor (idempotentno). */
    @DeleteMapping("/negotiations/{routingNumber}/{id}")
    public ResponseEntity<Void> closeNegotiation(
            @PathVariable int routingNumber,
            @PathVariable String id) {
        negotiationService.closeReceivedNegotiation(new ForeignBankId(routingNumber, id));
        return ResponseEntity.noContent().build();
    }

    /**
     * §3.6 — sinhrono prihvatanje. Vraca 204 No Content tek kad je 2PC committed
     * (spec §3.6: "Response: Empty (204 No Content)"). Greska u 2PC se manifestuje
     * kao izuzetak → 4xx/5xx u InterbankExceptionHandler-u.
     */
    @GetMapping("/negotiations/{routingNumber}/{id}/accept")
    public ResponseEntity<Void> acceptNegotiation(
            @PathVariable int routingNumber,
            @PathVariable String id) {
        // T2-F fix (Tim 1 cross-bank Stage C, 2026-05-20): prosledi sender
        // routing za turn-check (sender != lastModifier).
        Integer senderRouting = currentSenderRoutingOrNull();
        negotiationService.acceptReceivedNegotiation(new ForeignBankId(routingNumber, id), senderRouting);
        return ResponseEntity.noContent().build();
    }

    /** T2-F helper: izvuci sender routing iz {@code InterbankAuthFilter} principal-a, ili null. */
    private Integer currentSenderRoutingOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Integer rn) {
            return rn;
        }
        return null;
    }

    /**
     * §3.7 — friendly ime za nas lokalni id. 404 Not Found ako id ne postoji
     * (InterbankUserNotFoundException -> 404 kroz exception handler).
     */
    @GetMapping("/user/{routingNumber}/{id}")
    public ResponseEntity<UserInformation> getUserInfo(
            @PathVariable int routingNumber,
            @PathVariable String id) {
        // Spec §3.7: ako routingNumber nije nas, 404 (mi nismo autoritativni za stranu banku).
        if (routingNumber != negotiationService.requireMyRouting()) {
            throw new rs.raf.banka2_bek.interbank.exception.InterbankExceptions
                    .InterbankUserNotFoundException("routingNumber " + routingNumber + " nije nas");
        }
        return ResponseEntity.ok(negotiationService.serveUserInfo(id));
    }
}
