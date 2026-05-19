package rs.raf.trading.pricealert.model;

// ============================================================
// TODO [B5 - Cenovni alarmi (Price Alert) | Nosilac: Aleksa Vucinic]
//
// Enum koji opisuje uslov okidanja alarma na ceni hartije.
//
// IMPLEMENTIRATI:
//   - ABOVE: alarm se okida kada trzisna cena hartije predje (navis)
//            zadati prag (threshold). Primer: "obavesti me kad AAPL
//            predje 200 USD".
//   - BELOW: alarm se okida kada trzisna cena hartije padne (nanize)
//            ispod zadatog praga. Primer: "obavesti me kad AAPL padne
//            ispod 150 USD".
//   - Enum vrednosti su vec deklarisane ispod — ne dodavati nove bez
//     saglasnosti sa spec-om (Zadaci_Backend.pdf, zadatak B5).
//
// Konvencija: pratiti paket `savings` kao sablon.
// Spec: Zadaci_Backend.pdf, zadatak B5.
// ============================================================
public enum PriceAlertCondition {
    ABOVE,
    BELOW
}
