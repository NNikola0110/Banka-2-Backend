package rs.raf.banka2_bek.card.service;

import rs.raf.banka2_bek.card.dto.CardResponseDto;
import rs.raf.banka2_bek.card.dto.CreateCardRequestDto;
import rs.raf.banka2_bek.card.model.CardCategory;
import rs.raf.banka2_bek.card.model.CardType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface CardService {
    CardResponseDto createCard(CreateCardRequestDto request);
    CardResponseDto createCardForAccount(Long accountId, Long clientId, BigDecimal limit, CardType cardType);
    /** Verzija koja prosledjuje kategoriju + kreditni limit (za approve flow-a). */
    CardResponseDto createCardForAccount(Long accountId, Long clientId, BigDecimal limit, CardType cardType,
                                          CardCategory cardCategory, BigDecimal creditLimit);
    List<CardResponseDto> getMyCards();
    List<CardResponseDto> getCardsByAccount(Long accountId);
    CardResponseDto blockCard(Long cardId);
    CardResponseDto unblockCard(Long cardId);
    CardResponseDto deactivateCard(Long cardId);

    /**
     * C2 Sc32 (§spec): pokusaj aktivacije kartice. Deaktivirana kartica je
     * IREVERZIBILNA — pokusaj aktivacije baca {@link IllegalStateException} sa
     * literal spec porukom "Kartica je deaktivirana i ne moze se ponovo aktivirati".
     * Blokirana kartica se ne aktivira ovim putem (samo zaposleni preko
     * {@link #unblockCard}); vec aktivna je no-op (idempotentno vraca trenutno stanje).
     */
    CardResponseDto activateCard(Long cardId);

    CardResponseDto updateCardLimit(Long cardId, BigDecimal newLimit);

    /**
     * Dopuna INTERNET_PREPAID kartice — skida {@code amount} sa {@code sourceAccountId}
     * i povecava {@code Card.prepaidBalance}. Obe operacije u jednoj transakciji.
     * @throws IllegalStateException ako kartica nije INTERNET_PREPAID kategorije
     * @throws IllegalArgumentException ako iznos nije pozitivan ili nema dovoljno na racunu
     */
    CardResponseDto topUpPrepaidCard(Long cardId, Long sourceAccountId, BigDecimal amount);

    /**
     * P1-idempotency-1 (R5-1849): replay-safe dopuna. {@code idempotencyKey} je
     * klijent-generisan ({@code Idempotency-Key} header) — ako se isti zahtev posalje
     * dvaput (double-click, OkHttp retry-on-connection-failure, network retry), drugi
     * poziv vraca kesiran rezultat umesto da ponovo prebaci sredstva (exactly-once).
     * {@code null}/prazan kljuc → fallback na obican {@link #topUpPrepaidCard} (bez
     * dedup-a, ocuvano staro ponasanje za klijente koji jos ne salju kljuc).
     */
    CardResponseDto topUpPrepaidCard(Long cardId, Long sourceAccountId, BigDecimal amount, String idempotencyKey);

    /**
     * Povlacenje sredstava sa INTERNET_PREPAID kartice nazad na racun — obrnut smer od
     * top-up-a. Skida {@code amount} sa {@code Card.prepaidBalance} i dodaje na
     * {@code targetAccountId}. Atomicno u jednoj transakciji.
     * @throws IllegalStateException ako kartica nije INTERNET_PREPAID kategorije
     * @throws IllegalArgumentException ako iznos nije pozitivan ili nema dovoljno na kartici
     */
    CardResponseDto withdrawFromPrepaidCard(Long cardId, Long targetAccountId, BigDecimal amount);

    /**
     * P1-idempotency-1 (R5-1849): replay-safe povlacenje (simetricno sa
     * {@link #topUpPrepaidCard(Long, Long, BigDecimal, String)}).
     */
    CardResponseDto withdrawFromPrepaidCard(Long cardId, Long targetAccountId, BigDecimal amount, String idempotencyKey);

    /**
     * R1 317: prebacuje sve kartice kojima je {@code expirationDate < asOf} a jos
     * nisu DEACTIVATED u status DEACTIVATED. Bez ovoga istekla kartica ostaje
     * ACTIVE zauvek (usage gate je {@code status == ACTIVE} → istekla kartica bi
     * i dalje mogla da se koristi). Poziva ga {@code CardExpiryScheduler} dnevno.
     *
     * @param asOf referentni datum (obicno {@code LocalDate.now()})
     * @return broj kartica prebacenih u DEACTIVATED
     */
    int expireDueCards(LocalDate asOf);
}
