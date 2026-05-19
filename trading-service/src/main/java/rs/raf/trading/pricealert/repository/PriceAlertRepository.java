package rs.raf.trading.pricealert.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rs.raf.trading.pricealert.model.PriceAlert;

// ============================================================
// TODO [B5 - Cenovni alarmi (Price Alert) | Nosilac: Aleksa Vucinic]
//
// Spring Data JPA repozitorijum za entitet PriceAlert.
//
// IMPLEMENTIRATI (dodati custom query metode):
//   - List<PriceAlert> findByOwnerIdAndOwnerTypeOrderByCreatedAtDesc(
//         Long ownerId, String ownerType)
//       Vraca sve alarme datog korisnika (klijenta ili zaposlenog)
//       sortirane od najnovijeg. Koristiti za GET /price-alerts
//       (lista sopstvenih alarma).
//
//   - List<PriceAlert> findByListingIdAndActiveTrue(Long listingId)
//       Vraca sve aktivne alarme za konkretnu hartiju. Koristiti u
//       PriceAlertService.checkAlerts(listingId, currentPrice) tokom
//       ciklusa osvezavanja cena — metoda prolazi samo kroz aktivne
//       alarme kako bi minimizirala rad nad ugaslim alarmima.
//
//   - Optional<PriceAlert> findByIdAndOwnerIdAndOwnerType(
//         Long id, Long ownerId, String ownerType)
//       Kombinovani lookup po id + vlasniku; koristi se u DELETE/GET
//       radi provere vlasnistva bez dodatnog service-level poziva.
//
//   - void deleteByOwnerIdAndOwnerType(Long ownerId, String ownerType)
//       Bulk brisanje svih alarma korisnika. Korisno pri brisanju
//       naloga (GDPR cleanup), ali takodje moze biti izlozeno kao
//       administratorska akcija.
//
// Konvencija: pratiti paket `savings` kao sablon.
// Spec: Zadaci_Backend.pdf, zadatak B5.
// ============================================================
public interface PriceAlertRepository extends JpaRepository<PriceAlert, Long> {
}
