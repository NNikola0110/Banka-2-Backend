package rs.raf.trading.recurringorder.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// ============================================================
// TODO [B8 - Trajni nalozi (DCA / RecurringOrder) | Nosilac: Nikola Djurovic]
//
// REST kontroler za upravljanje trajnim nalozima.
// Base path: /recurring-orders
//
// IMPLEMENTIRATI — dodati sledece endpoint metode:
//
// SECURITY: pravilo `/recurring-orders/**` (authenticated()) je vec dodato u
//   TradingSecurityConfig (paket rs.raf.trading.security); api-gateway
//   (nginx.conf) ima `location /recurring-orders` koji rutira na
//   trading-service:8082.
//
//   POST /recurring-orders
//       @Valid @RequestBody CreateRecurringOrderDto dto
//       -> ResponseEntity<RecurringOrderDto> (HTTP 201 Created)
//       -> Poziva RecurringOrderService.create(dto)
//       -> Dostupno: autentifikovani klijenti i zaposleni (actuary)
//
//   GET /recurring-orders
//       -> ResponseEntity<List<RecurringOrderDto>> (HTTP 200)
//       -> Poziva RecurringOrderService.listMy()
//       -> Vraca samo naloge trenutno ulogovanog korisnika
//
//   GET /recurring-orders/{id}
//       -> ResponseEntity<RecurringOrderDto> (HTTP 200)
//       -> Poziva RecurringOrderService.getById(id)
//       -> Service baca 404 ako nalog ne postoji, 403 ako nije vlasnik
//
//   PATCH /recurring-orders/{id}/pause
//       -> ResponseEntity<RecurringOrderDto> (HTTP 200)
//       -> Poziva RecurringOrderService.pause(id)
//       -> Pauzira aktivni nalog (active = false), ne brise ga
//
//   PATCH /recurring-orders/{id}/resume
//       -> ResponseEntity<RecurringOrderDto> (HTTP 200)
//       -> Poziva RecurringOrderService.resume(id)
//       -> Reaktivira pauzirani nalog (active = true);
//          nextRun se postavlja na now + 1 cadence korak kako se ne bi
//          odmah izvrsio u sledecem scheduler prolazu
//
//   DELETE /recurring-orders/{id}
//       -> ResponseEntity<Void> (HTTP 204 No Content)
//       -> Poziva RecurringOrderService.cancel(id)
//       -> Otkazuje i uklanja nalog
//
// Injectovati RecurringOrderService kao final polje.
// Koristiti @PathVariable Long id i ResponseEntity<> konzistentno sa
// ostalim kontrolerima u trading-service-u (videti InvestmentFundController).
//
// Konvencija: pratiti paket `savings` kao sablon.
// Spec: Zadaci_Backend.pdf, zadatak B8.
// ============================================================
@RestController
@RequestMapping("/recurring-orders")
@RequiredArgsConstructor
public class RecurringOrderController {
}
