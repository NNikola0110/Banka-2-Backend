package rs.raf.banka2_bek.card.model;

/**
 * Kategorija kartice — odredjuje izvor sredstava za placanje.
 *
 * <ul>
 *   <li>{@link #DEBIT} — direktna debitacija sa povezanog Account-a (default, postojeci behavior)</li>
 *   <li>{@link #CREDIT} — kreditna kartica sa rate-ama. Banka unapred odobrava {@code creditLimit};
 *       placanja se akumuliraju u {@code outstandingBalance} i otplacuju mesecno.</li>
 *   <li>{@link #INTERNET_PREPAID} — odvojen {@code prepaidBalance} koji se top-up-uje sa Account-a.
 *       Klijent prebacuje pare unapred — odlicno za internet kupovine (limit izlozenosti).</li>
 * </ul>
 *
 * Spec C2 §266: "Vrste kartica koje indektifikujemo po MII i IIN" odnosi se na BREND (VISA/MC/DC/AMEX).
 * Ovaj enum je {@code CARDS} type-of-payment categorija — ortogonalan na {@link CardType} brend.
 *
 * <p><b>R1-636 (dokumentovano ogranicenje):</b> {@code CREDIT} je trenutno
 * KOZMETICKA kategorija — {@code Card.creditLimit}/{@code outstandingBalance} se
 * cuvaju i prikazuju, ali nijedan payment/transakcioni put ih NE zaduzuje (nema
 * kreditne linije, rata ni mesecne otplate). Funkcionalan credit-line je feature,
 * ne P3 cleanup; do tada CREDIT kartica trosi kao DEBIT (direktan debit Account-a).</p>
 */
public enum CardCategory {
    DEBIT,
    CREDIT,
    INTERNET_PREPAID
}
