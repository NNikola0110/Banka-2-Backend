package rs.raf.banka2_bek.card.service.implementation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountType;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.card.dto.CardResponseDto;
import rs.raf.banka2_bek.card.dto.CreateCardRequestDto;
import rs.raf.banka2_bek.card.model.Card;
import rs.raf.banka2_bek.card.model.CardCategory;
import rs.raf.banka2_bek.card.model.CardStatus;
import rs.raf.banka2_bek.card.model.CardType;
import rs.raf.banka2_bek.card.repository.CardRepository;
import rs.raf.banka2_bek.card.service.CardService;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.internalapi.service.InternalIdempotencyService;
import rs.raf.banka2_bek.notification.NotificationPublisher;
import rs.raf.banka2_bek.notification.model.NotificationType;
import rs.raf.banka2_bek.notification.service.NotificationService;
import rs.raf.banka2_bek.audit.model.AuditActionType;
import rs.raf.banka2_bek.audit.service.AuditLogService;
import rs.raf.banka2_bek.audit.service.CurrentAuditActorResolver;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CardServiceImpl implements CardService {

    // R1-639: izvuceni magic brojevi/stringovi iz koda u imenovane konstante.
    /** Podrazumevani limit kartice kad klijent/zaposleni ne posalje vrednost. */
    private static final BigDecimal DEFAULT_CARD_LIMIT = BigDecimal.valueOf(100000);
    /** Validnost kartice u godinama (od izdavanja do isteka). */
    private static final int CARD_VALIDITY_YEARS = 4;
    /** Srednji deo maske broja kartice — 8 zvezdica (Celina 2 §321: 5798********5571). */
    private static final String CARD_MASK_MIDDLE = "********";

    private final CardRepository cardRepository;
    private final AccountRepository accountRepository;
    private final ClientRepository clientRepository;
    private final NotificationPublisher notificationPublisher;
    private final NotificationService notificationService;
    // BE-PAY-01: audit log za card lifecycle (block/unblock/limit change)
    private final AuditLogService auditLogService;
    // P2-audit-coverage-1 (R5 1891): aktor = IZVRSILAC (iz SecurityContext), ne vlasnik kartice.
    private final CurrentAuditActorResolver currentAuditActorResolver;
    // P1-idempotency-1 (R5-1849): replay-guard za prepaid top-up/withdraw. Deli
    // `internal_requests` dedup store (UNIQUE na idempotencyKey, atomic same-tx
    // insert) — isti mehanizam koji SAGA/internal-funds koriste, bez novog
    // PG-DDL-a. Optional collaborator: kad kljuc nije prosledjen (stari klijenti)
    // ili u unit testovima koji ne mockuju ovaj bean, ostaje null i ne dira se.
    private final InternalIdempotencyService internalIdempotencyService;

    /**
     * BE-PAY-01 audit hook helper: best-effort.
     */
    private void recordAuditSafe(Long actorId, String actorType, AuditActionType action,
                                 String description, String targetType, Long targetId) {
        try {
            auditLogService.record(actorId, actorType, action, description, targetType, targetId);
        } catch (Exception e) {
            log.warn("Audit log fail (best-effort) action={} target={}/{}: {}",
                    action, targetType, targetId, e.getMessage());
        }
    }

    /**
     * P2-audit-coverage-1 (R5 1891): audit hook gde je AKTOR izvrsilac iz
     * SecurityContext-a (ne vlasnik kartice). Best-effort + null-safe (resolver
     * je opcioni collaborator — u unit testovima koji ga ne mockuju ostaje null,
     * pa pada na SYSTEM aktora). targetId nosi pogodjeni resurs (kartica).
     */
    private void recordAuditByCurrentActor(AuditActionType action, String description,
                                           String targetType, Long targetId) {
        try {
            CurrentAuditActorResolver.AuditActor actor = currentAuditActorResolver != null
                    ? currentAuditActorResolver.resolveCurrentActor()
                    : CurrentAuditActorResolver.SYSTEM;
            auditLogService.record(actor.actorId(), actor.actorType(), action,
                    description, targetType, targetId);
        } catch (Exception e) {
            log.warn("Audit log fail (best-effort) action={} target={}/{}: {}",
                    action, targetType, targetId, e.getMessage());
        }
    }

    @Override
    @Transactional
    public CardResponseDto createCard(CreateCardRequestDto request) {
        // SC28/T2-007 fix (14.05.2026): kad zaposleni pravi karticu u ime klijenta,
        // JWT principal je email zaposlenog (NIJE klijent), pa getAuthenticatedClient()
        // ne nadje klijenta i puca "Klijent nije pronadjen". Resenje: za EMPLOYEE/ADMIN
        // role resolve klijenta preko account.getClient(); za CLIENT zadrzi ownership check.
        boolean callerIsEmployee = isCallerEmployeeOrAdmin();
        Client client;
        if (callerIsEmployee) {
            // Auth check (employee mora biti autentifikovan da bismo dosli ovde).
            requireAuthenticated();
        } else {
            client = getAuthenticatedClient();
            Account account = accountRepository.findById(request.getAccountId())
                    .orElseThrow(() -> new RuntimeException("Racun nije pronadjen"));
            if (account.getClient() == null || !account.getClient().getId().equals(client.getId())) {
                throw new RuntimeException("Nemate pristup ovom racunu");
            }
            checkCardLimit(account, client);
            BigDecimal limit = request.getCardLimit() != null ? request.getCardLimit() : DEFAULT_CARD_LIMIT;
            CardType cardType = request.getCardType() != null ? request.getCardType() : CardType.VISA;
            CardCategory cardCategory = request.getCardCategory() != null ? request.getCardCategory() : CardCategory.DEBIT;
            BigDecimal creditLimit = request.getCreditLimit() != null ? request.getCreditLimit() : BigDecimal.ZERO;
            return toResponse(createAndSaveCard(account, client, limit, cardType, cardCategory, creditLimit));
        }

        // Employee/Admin path: lookup account, resolve client from account
        Account account = accountRepository.findById(request.getAccountId())
                .orElseThrow(() -> new RuntimeException("Racun nije pronadjen"));
        client = account.getClient();
        if (client == null) {
            throw new RuntimeException("Racun nema vlasnika (klijenta)");
        }
        checkCardLimit(account, client);
        BigDecimal limit = request.getCardLimit() != null ? request.getCardLimit() : DEFAULT_CARD_LIMIT;
        CardType cardType = request.getCardType() != null ? request.getCardType() : CardType.VISA;
        CardCategory cardCategory = request.getCardCategory() != null ? request.getCardCategory() : CardCategory.DEBIT;
        BigDecimal creditLimit = request.getCreditLimit() != null ? request.getCreditLimit() : BigDecimal.ZERO;
        return toResponse(createAndSaveCard(account, client, limit, cardType, cardCategory, creditLimit));
    }

    @Override
    @Transactional
    public CardResponseDto createCardForAccount(Long accountId, Long clientId, BigDecimal limit, CardType cardType) {
        return createCardForAccount(accountId, clientId, limit, cardType, CardCategory.DEBIT, BigDecimal.ZERO);
    }

    @Override
    @Transactional
    public CardResponseDto createCardForAccount(Long accountId, Long clientId, BigDecimal limit, CardType cardType,
                                                 CardCategory cardCategory, BigDecimal creditLimit) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new RuntimeException("Racun nije pronadjen"));
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Klijent nije pronadjen"));

        checkCardLimit(account, client);

        BigDecimal cardLimit = limit != null ? limit : DEFAULT_CARD_LIMIT;
        CardType type = cardType != null ? cardType : CardType.VISA;
        CardCategory cat = cardCategory != null ? cardCategory : CardCategory.DEBIT;
        BigDecimal credit = creditLimit != null ? creditLimit : BigDecimal.ZERO;
        return toResponse(createAndSaveCard(account, client, cardLimit, type, cat, credit));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CardResponseDto> getMyCards() {
        Client client = getOptionalClient();
        if (client == null) return Collections.emptyList();
        return cardRepository.findByClientId(client.getId()).stream()
                .map(this::toMaskedResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CardResponseDto> getCardsByAccount(Long accountId) {
        // P0-B9 N2 (IDOR): bez ove provere bilo koji klijent je mogao da procita
        // sve kartice TUDJEG racuna (ime vlasnika + saldi/limiti) sekvencijalnim
        // accountId-em. CLIENT sme samo kartice racuna koji mu pripada; EMPLOYEE/
        // ADMIN (employee portal) bilo koji; no-auth interni poziv (scheduler/unit)
        // prolazi nepromenjen.
        assertAccountAccessibleByCaller(accountId);
        return cardRepository.findByAccountId(accountId).stream()
                .map(this::toMaskedResponse)
                .collect(Collectors.toList());
    }

    /**
     * P0-B9 N2 ownership guard za {@code getCardsByAccount}. Baca
     * {@link AccessDeniedException} (-&gt; HTTP 403) ako je pozivalac CLIENT a
     * racun mu ne pripada. Zaposleni (ROLE_EMPLOYEE/ROLE_ADMIN/SUPERVISOR) i
     * nezasticeni interni pozivi (bez auth konteksta — unit testovi/scheduleri)
     * prolaze.
     */
    private void assertAccountAccessibleByCaller(Long accountId) {
        if (isCallerEmployeeOrAdmin()) return;
        Client client = getOptionalClient();
        if (client == null) return; // no-auth / interni — ne diramo (B8 obrazac)
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) return; // nepostojeci racun -> prazna lista (ne otkrivamo postojanje)
        boolean isOwner = account.getClient() != null
                && account.getClient().getId().equals(client.getId());
        boolean isCompanyAuthorized = account.getCompany() != null
                && account.getCompany().getAuthorizedPersons() != null
                && account.getCompany().getAuthorizedPersons().stream()
                        .anyMatch(ap -> ap.getClient() != null
                                && ap.getClient().getId().equals(client.getId()));
        if (!isOwner && !isCompanyAuthorized) {
            throw new AccessDeniedException("Nemate pristup karticama ovog racuna.");
        }
    }

    @Override
    @Transactional
    public CardResponseDto blockCard(Long cardId) {
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new RuntimeException("Kartica nije pronadjena"));

        // Samo vlasnik kartice moze da je blokira
        Client client = getOptionalClient();
        if (client != null && (card.getClient() == null || !card.getClient().getId().equals(client.getId()))) {
            throw new RuntimeException("Nemate pristup ovoj kartici");
        }

        if (card.getStatus() == CardStatus.DEACTIVATED) {
            throw new RuntimeException("Deaktivirana kartica se ne moze blokirati. Deaktivirane kartice ne mogu biti ponovo aktivirane.");
        }
        if (card.getStatus() == CardStatus.BLOCKED) {
            throw new RuntimeException("Kartica je vec blokirana");
        }
        card.setStatus(CardStatus.BLOCKED);
        CardResponseDto response = toMaskedResponse(cardRepository.save(card));

        try {
            String last4 = card.getCardNumber().substring(card.getCardNumber().length() - 4);
            notificationPublisher.sendCardBlockedMail(
                    card.getClient().getEmail(), last4, LocalDate.now());
        } catch (Exception e) {
            log.warn("Failed to send card notification email", e);
        }

        if (card.getClient() != null) {
            try {
                notificationService.notify(
                        card.getClient().getId(),
                        "CLIENT",
                        NotificationType.CARD_BLOCKED,
                        "Kartica blokirana",
                        "Vaša kartica je blokirana.",
                        "CARD",
                        card.getId()
                );
            } catch (Exception e) {
                log.warn("Failed to send card blocked notification: {}", e.getMessage());
            }
        }

        // BE-PAY-01 + P2-audit-coverage-1 (R5 1891): aktor = IZVRSILAC (zaposleni
        // ili sam klijent), ne vlasnik kartice. Vlasnik ostaje u targetId/opisu.
        Long ownerId = card.getClient() != null ? card.getClient().getId() : null;
        recordAuditByCurrentActor(
                AuditActionType.CARD_BLOCKED,
                "Card " + cardId + " blocked (owner client=" + ownerId + ")",
                "CARD", cardId);

        return response;
    }

    @Override
    @Transactional
    public CardResponseDto unblockCard(Long cardId) {
        // P0-B8 N3: odblokiranje je EMPLOYEE/ADMIN akcija (klijent moze samo da
        // BLOKIRA svoju karticu — vidi blockCard — ali ne i da je odblokira).
        // HTTP put je zasticen URL-om (/cards/*/unblock hasAnyRole ADMIN,EMPLOYEE),
        // ali agentic in-process poziv (UnblockCardActionHandler) zaobilazi
        // kontroler i URL security. Bez ovog guard-a, klijent je mogao kroz
        // Arbitro agenta da odblokira BILO KOJU karticu. Guard odbija
        // autentifikovanog CLIENT-a.
        assertNotClientCaller("Odblokiranje kartice je dozvoljeno samo zaposlenima.");
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new RuntimeException("Kartica nije pronadjena"));

        if (card.getStatus() != CardStatus.BLOCKED) {
            throw new RuntimeException("Samo blokirana kartica se moze deblokirati");
        }
        card.setStatus(CardStatus.ACTIVE);
        CardResponseDto response = toMaskedResponse(cardRepository.save(card));

        try {
            String last4 = card.getCardNumber().substring(card.getCardNumber().length() - 4);
            notificationPublisher.sendCardUnblockedMail(
                    card.getClient().getEmail(), last4);
        } catch (Exception e) {
            log.warn("Failed to send card notification email", e);
        }

        if (card.getClient() != null) {
            try {
                notificationService.notify(
                        card.getClient().getId(),
                        "CLIENT",
                        NotificationType.CARD_UNBLOCKED,
                        "Kartica odblokirana",
                        "Vaša kartica je uspešno odblokirana.",
                        "CARD",
                        card.getId()
                );
            } catch (Exception e) {
                log.warn("Failed to send card unblocked notification: {}", e.getMessage());
            }
        }

        // BE-PAY-01 + P2-audit-coverage-1 (R5 1891): aktor = IZVRSILAC (zaposleni —
        // klijent ne sme odblokirati, assertNotClientCaller iznad), ne vlasnik kartice.
        Long ownerId = card.getClient() != null ? card.getClient().getId() : null;
        recordAuditByCurrentActor(
                AuditActionType.CARD_UNBLOCKED,
                "Card " + cardId + " unblocked (owner client=" + ownerId + ")",
                "CARD", cardId);

        return response;
    }

    @Override
    @Transactional
    public CardResponseDto deactivateCard(Long cardId) {
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new RuntimeException("Kartica nije pronadjena"));

        if (card.getStatus() == CardStatus.DEACTIVATED) {
            throw new RuntimeException("Kartica je vec deaktivirana. Deaktivirane kartice ne mogu biti ponovo aktivirane.");
        }

        card.setStatus(CardStatus.DEACTIVATED);
        CardResponseDto deactivateResponse = toMaskedResponse(cardRepository.save(card));

        // P2-audit-coverage-1 (R5 1890): trajna (ireverzibilna) deaktivacija kartice je
        // najosetljivija card-lifecycle akcija a jedina je bila bez audit traga (block/
        // unblock/limit-change jesu). Aktor = izvrsilac iz SecurityContext.
        Long ownerId = card.getClient() != null ? card.getClient().getId() : null;
        recordAuditByCurrentActor(
                AuditActionType.CARD_DEACTIVATED,
                "Card " + cardId + " deactivated (owner client=" + ownerId + ")",
                "CARD", cardId);

        return deactivateResponse;
    }

    @Override
    @Transactional
    public CardResponseDto activateCard(Long cardId) {
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new RuntimeException("Kartica nije pronadjena"));

        // C2 Sc32: deaktivirana kartica je IREVERZIBILNA — literal spec poruka.
        // IllegalStateException → 403 (CardExceptionHandler). Invarijanta drzi: nigde
        // u kodu ne postoji put DEACTIVATED→ACTIVE, ovaj endpoint to eksplicitno odbija.
        if (card.getStatus() == CardStatus.DEACTIVATED) {
            throw new IllegalStateException(
                    "Kartica je deaktivirana i ne može se ponovo aktivirati");
        }

        // Blokirana kartica se aktivira iskljucivo preko zaposlenog (unblockCard);
        // ovaj put je ne dira (klijent ne sme da je odblokira sam).
        if (card.getStatus() == CardStatus.BLOCKED) {
            throw new IllegalStateException(
                    "Blokiranu karticu može da odblokira samo zaposleni u banci.");
        }

        // Vec aktivna — idempotentno vraca trenutno stanje (bez izmene).
        return toMaskedResponse(card);
    }

    @Override
    @Transactional
    public CardResponseDto updateCardLimit(Long cardId, BigDecimal newLimit) {
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new RuntimeException("Kartica nije pronadjena"));

        if (card.getStatus() == CardStatus.DEACTIVATED) {
            throw new RuntimeException("Ne moze se menjati limit deaktivirane kartice");
        }
        BigDecimal oldLimit = card.getCardLimit();
        card.setCardLimit(newLimit);
        CardResponseDto limitResponse = toMaskedResponse(cardRepository.save(card));

        if (card.getClient() != null) {
            try {
                notificationService.notify(
                        card.getClient().getId(),
                        "CLIENT",
                        NotificationType.LIMIT_CHANGE,
                        "Limit kartice promenjen",
                        "Limit vaše kartice je uspešno promenjen na " + newLimit + ".",
                        "CARD",
                        card.getId()
                );
            } catch (Exception e) {
                log.warn("Failed to send card limit change notification: {}", e.getMessage());
            }
        }

        // BE-PAY-01 + P2-audit-coverage-1 (R5 1891): aktor = IZVRSILAC (iz SecurityContext),
        // ne vlasnik kartice. old/new limit u oldValue/newValue.
        Long ownerId = card.getClient() != null ? card.getClient().getId() : null;
        try {
            CurrentAuditActorResolver.AuditActor actor = currentAuditActorResolver != null
                    ? currentAuditActorResolver.resolveCurrentActor()
                    : CurrentAuditActorResolver.SYSTEM;
            auditLogService.record(
                    actor.actorId(), actor.actorType(),
                    AuditActionType.CARD_LIMIT_CHANGED,
                    "Card " + cardId + " limit changed (owner client=" + ownerId + ")",
                    "CARD", cardId,
                    String.valueOf(oldLimit), String.valueOf(newLimit));
        } catch (Exception e) {
            log.warn("Audit log fail (best-effort) action=CARD_LIMIT_CHANGED target=CARD/{}: {}",
                    cardId, e.getMessage());
        }

        return limitResponse;
    }

    // --- helpers ---

    private void checkCardLimit(Account account, Client client) {
        boolean isBusiness = account.getAccountType() == AccountType.BUSINESS;

        if (isBusiness) {
            // Poslovni racun: max 1 kartica po ovlascenom licu (klijentu)
            long activeCardsForPerson = cardRepository.countByAccountIdAndClientIdAndStatusNot(
                    account.getId(), client.getId(), CardStatus.DEACTIVATED);
            if (activeCardsForPerson >= 1) {
                throw new RuntimeException("Ovlasceno lice vec ima karticu za ovaj poslovni racun (max 1 po osobi)");
            }
        } else {
            // Licni racun: max 2 kartice ukupno
            long activeCards = cardRepository.countByAccountIdAndStatusNot(account.getId(), CardStatus.DEACTIVATED);
            if (activeCards >= 2) {
                throw new RuntimeException("Dostignut maksimalan broj kartica za ovaj racun (2)");
            }
        }
    }

    /**
     * P2.3 — alocira slot 1 ili 2 za novu karticu. Slot je deo
     * partial UNIQUE INDEX-a (account_id, client_id, card_slot) gde
     * status != 'DEACTIVATED' — DB-level enforcement preventiva za
     * race-condition (dva paralelna createCard mogu da prodju kroz
     * service-level checkCardLimit ali DB ce odbiti drugi insert).
     *
     * @return 1 ili 2 (uvek 1 za poslovne, 1 ili 2 za licne)
     * @throws RuntimeException ako su svi slotovi zauzeti
     */
    private int allocateSlot(Account account, Client client) {
        boolean isBusiness = account.getAccountType() == AccountType.BUSINESS;
        // Aktivne kartice ZA TAJ par (account, client) — slotovi su per-par-ovi.
        List<Card> existing = cardRepository.findByAccountIdAndClientIdAndStatusNot(
                account.getId(), client.getId(), CardStatus.DEACTIVATED);
        java.util.Set<Integer> taken = existing.stream()
                .map(Card::getCardSlot)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        if (isBusiness) {
            // Poslovni: slot uvek 1 (max 1 kartica po osobi)
            if (taken.contains(1)) {
                throw new RuntimeException("Ovlasceno lice vec ima karticu za ovaj poslovni racun (slot 1 zauzet)");
            }
            return 1;
        }

        // Licni: 1 ili 2
        if (!taken.contains(1)) return 1;
        if (!taken.contains(2)) return 2;
        throw new RuntimeException("Dostignut maksimalan broj kartica za ovaj racun (2)");
    }

    private Card createAndSaveCard(Account account, Client client, BigDecimal limit, CardType cardType) {
        return createAndSaveCard(account, client, limit, cardType, CardCategory.DEBIT, BigDecimal.ZERO);
    }

    private Card createAndSaveCard(Account account, Client client, BigDecimal limit, CardType cardType,
                                   CardCategory cardCategory, BigDecimal creditLimit) {
        String cardNumber;
        do {
            cardNumber = Card.generateCardNumber(cardType);
        } while (cardRepository.findByCardNumber(cardNumber).isPresent());

        int slot = allocateSlot(account, client);

        Card card = Card.builder()
                .cardNumber(cardNumber)
                .cardName(resolveCardName(cardType, cardCategory))
                .cardType(cardType)
                .cardCategory(cardCategory != null ? cardCategory : CardCategory.DEBIT)
                .creditLimit(cardCategory == CardCategory.CREDIT && creditLimit != null
                        ? creditLimit : BigDecimal.ZERO)
                .prepaidBalance(BigDecimal.ZERO)
                .outstandingBalance(BigDecimal.ZERO)
                // R1-500-CVV (PCI-DSS Req 3.2): CVV se VISE NE CUVA at-rest. Polje je
                // @Transient na entitetu i nikad se ne verifikuje, pa ga ovde ni ne
                // generisemo/postavljamo — kartica se kreira bez perzistovanog CVV-a.
                .account(account)
                .client(client)
                .cardLimit(limit)
                .cardSlot(slot)
                .status(CardStatus.ACTIVE)
                .createdAt(LocalDate.now())
                .expirationDate(LocalDate.now().plusYears(CARD_VALIDITY_YEARS))
                .build();

        return cardRepository.save(card);
    }

    private String resolveCardName(CardType cardType, CardCategory cardCategory) {
        String brand = switch (cardType) {
            case VISA -> "Visa";
            case MASTERCARD -> "MasterCard";
            case DINACARD -> "DinaCard";
            case AMERICAN_EXPRESS -> "American Express";
        };
        CardCategory cat = cardCategory != null ? cardCategory : CardCategory.DEBIT;
        // American Express je historijski charge brend — ne dodajemo "Debit" suffix.
        // Za CREDIT i INTERNET_PREPAID svi brendovi (ukljucujuci AMEX) dobijaju suffix.
        if (cardType == CardType.AMERICAN_EXPRESS && cat == CardCategory.DEBIT) {
            return brand;
        }
        String suffix = switch (cat) {
            case DEBIT -> " Debit";
            case CREDIT -> " Credit";
            case INTERNET_PREPAID -> " Prepaid";
        };
        return brand + suffix;
    }

    private String resolveCardName(CardType cardType) {
        return resolveCardName(cardType, CardCategory.DEBIT);
    }

    /**
     * BE-ACC-01 (PCI-DSS): CVV nikad ne sme da napusti server posle izdavanja
     * kartice. R1-500-CVV: CVV se vise NE CUVA at-rest ({@code Card.cvv} je
     * {@code @Transient}, kolona uklonjena), pa je ovde uvek {@code null};
     * DTO uvek vraca {@code null} bez obzira na endpoint
     * (create / get / top-up / withdraw). {@code cardNumber} se vraca u
     * punom obliku samo na createCard/createCardForAccount (jednom — posiljaocu
     * obavestenja); maskirani prikaz koristi {@link #toMaskedResponse(Card)}.
     */
    private CardResponseDto toResponse(Card card) {
        return CardResponseDto.builder()
                .id(card.getId())
                .cardNumber(card.getCardNumber())
                .cardName(card.getCardName())
                // BE-ACC-01: CVV nikad ne izlazi sa BE-a (PCI-DSS).
                .cvv(null)
                .cardType(card.getCardType())
                .cardCategory(card.getCardCategory())
                .accountId(card.getAccount().getId())
                .accountNumber(card.getAccount().getAccountNumber())
                .ownerName(card.getClient().getFirstName() + " " + card.getClient().getLastName())
                .cardLimit(card.getCardLimit())
                .prepaidBalance(card.getPrepaidBalance())
                .creditLimit(card.getCreditLimit())
                .outstandingBalance(card.getOutstandingBalance())
                .status(card.getStatus())
                .createdAt(card.getCreatedAt())
                .expirationDate(card.getExpirationDate())
                .build();
    }

    private CardResponseDto toMaskedResponse(Card card) {
        return CardResponseDto.builder()
                .id(card.getId())
                .cardNumber(maskCardNumber(card.getCardNumber()))
                .cardName(card.getCardName())
                .cvv(null)
                .cardType(card.getCardType())
                .cardCategory(card.getCardCategory())
                .accountId(card.getAccount().getId())
                .accountNumber(card.getAccount().getAccountNumber())
                .ownerName(card.getClient().getFirstName() + " " + card.getClient().getLastName())
                .cardLimit(card.getCardLimit())
                .prepaidBalance(card.getPrepaidBalance())
                .creditLimit(card.getCreditLimit())
                .outstandingBalance(card.getOutstandingBalance())
                .status(card.getStatus())
                .createdAt(card.getCreatedAt())
                .expirationDate(card.getExpirationDate())
                .build();
    }

    /**
     * Maskira broj kartice u format iz Celine 2 §321: prve 4 cifre, potom 8
     * zvezdica, pa zadnje 4 cifre (primer {@code 5798********5571} — bez razmaka).
     * Kartice krace od 8 cifara se vracaju nepromenjene.
     */
    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 8) return cardNumber;
        String digits = cardNumber.replaceAll("\\s+", "");
        String first4 = digits.substring(0, 4);
        String last4 = digits.substring(digits.length() - 4);
        return first4 + CARD_MASK_MIDDLE + last4;
    }

    private Client getAuthenticatedClient() {
        Client client = getOptionalClient();
        if (client == null) throw new RuntimeException("Klijent nije pronadjen");
        return client;
    }

    private Client getOptionalClient() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        String email;
        if (principal instanceof UserDetails userDetails) {
            email = userDetails.getUsername();
        } else {
            email = principal.toString();
        }
        return clientRepository.findByEmail(email).orElse(null);
    }

    @Override
    @Transactional
    public CardResponseDto topUpPrepaidCard(Long cardId, Long sourceAccountId, BigDecimal amount) {
        return topUpPrepaidCard(cardId, sourceAccountId, amount, null);
    }

    @Override
    @Transactional
    public CardResponseDto topUpPrepaidCard(Long cardId, Long sourceAccountId, BigDecimal amount,
                                            String idempotencyKey) {
        // P1-idempotency-1 (R5-1849): replay guard. P2-2 pessimistic lock samo
        // SERIJALIZUJE konkurentne pozive (lost-update), ali DVA SEKVENCIJALNA
        // identicna zahteva (double-click / OkHttp retry / network retry) oba
        // prodju — drugi cita vec-azuriran balans i ponovo primeni transfer =
        // dvostruka dopuna. Klijent-generisan Idempotency-Key cini operaciju
        // exactly-once: na replay vracamo trenutno (jednom-azurirano) stanje kartice.
        String dedupKey = normalizeIdempotencyKey("card-topup", cardId, idempotencyKey);
        if (dedupKey != null && internalIdempotencyService != null
                && internalIdempotencyService.findCached(dedupKey).isPresent()) {
            Card existing = cardRepository.findByIdForUpdate(cardId)
                    .orElseThrow(() -> new RuntimeException("Kartica nije pronadjena"));
            return toResponse(existing);
        }

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Iznos dopune mora biti veci od 0.");
        }
        // P2-2 lost-update fix: pessimistic lock NA KARTICI serijalizuje paralelne
        // top-up-e iste kartice. Lock samo na sourceAccount (ispod) ne pomaze kad
        // dve dopune dolaze iz RAZLICITIH racuna — zakljucaju razlicite redove i
        // oba pregaze isti prepaidBalance read.
        Card card = cardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new RuntimeException("Kartica nije pronadjena"));

        // P1-authz-idor-1 (R1 115): ownership PRE provere stanja/kategorije —
        // inace su poruke "nije INTERNET_PREPAID" / "nije aktivna" curile postojanje
        // i tip tudje kartice klijentu koji nije vlasnik. CLIENT samo svoju karticu;
        // EMPLOYEE/ADMIN bilo koju.
        if (!isCallerEmployeeOrAdmin()) {
            Client client = getAuthenticatedClient();
            if (card.getClient() == null || !card.getClient().getId().equals(client.getId())) {
                throw new RuntimeException("Nemate pristup ovoj kartici");
            }
        }

        if (card.getCardCategory() != CardCategory.INTERNET_PREPAID) {
            throw new IllegalStateException("Dopuna je dostupna samo za INTERNET_PREPAID kartice.");
        }
        if (card.getStatus() != CardStatus.ACTIVE) {
            throw new IllegalStateException("Kartica nije aktivna — nije moguca dopuna.");
        }

        // BE-ACC-03: pessimistic lock na sourceAccount sprecava lost-update race
        // pri paralelnim top-up zahtevima za isti racun (read→check→write bez
        // lock-a bi dozvolio overdraft).
        Account sourceAccount = accountRepository.findForUpdateById(sourceAccountId)
                .orElseThrow(() -> new RuntimeException("Racun nije pronadjen"));
        // Vlasnik racuna mora biti vlasnik kartice.
        if (sourceAccount.getClient() == null
                || !sourceAccount.getClient().getId().equals(card.getClient().getId())) {
            throw new RuntimeException("Racun za dopunu mora pripadati vlasniku kartice.");
        }
        if (sourceAccount.getAvailableBalance() == null
                || sourceAccount.getAvailableBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Nedovoljno sredstava na racunu: potrebno " + amount);
        }

        sourceAccount.setBalance(sourceAccount.getBalance().subtract(amount));
        sourceAccount.setAvailableBalance(sourceAccount.getAvailableBalance().subtract(amount));
        accountRepository.save(sourceAccount);

        card.setPrepaidBalance(card.getPrepaidBalance().add(amount));
        CardResponseDto response = toResponse(cardRepository.save(card));
        recordIdempotencyMarker(dedupKey, "card-topup");
        return response;
    }

    @Override
    @Transactional
    public CardResponseDto withdrawFromPrepaidCard(Long cardId, Long targetAccountId, BigDecimal amount) {
        return withdrawFromPrepaidCard(cardId, targetAccountId, amount, null);
    }

    @Override
    @Transactional
    public CardResponseDto withdrawFromPrepaidCard(Long cardId, Long targetAccountId, BigDecimal amount,
                                                   String idempotencyKey) {
        // P1-idempotency-1 (R5-1849): replay guard (simetricno sa top-up putanjom).
        String dedupKey = normalizeIdempotencyKey("card-withdraw", cardId, idempotencyKey);
        if (dedupKey != null && internalIdempotencyService != null
                && internalIdempotencyService.findCached(dedupKey).isPresent()) {
            Card existing = cardRepository.findByIdForUpdate(cardId)
                    .orElseThrow(() -> new RuntimeException("Kartica nije pronadjena"));
            return toResponse(existing);
        }

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Iznos povlacenja mora biti veci od 0.");
        }
        // P2-2 lost-update fix: pessimistic lock NA KARTICI serijalizuje paralelne
        // mutacije prepaidBalance-a iste kartice (simetricno sa top-up putanjom).
        Card card = cardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new RuntimeException("Kartica nije pronadjena"));

        // P1-authz-idor-1 (R1 115): ownership PRE provere stanja/balansa — inace
        // su poruke "nije INTERNET_PREPAID" / "Nedovoljno sredstava: dostupno X"
        // curile postojanje, tip i prepaid balans tudje kartice ne-vlasniku.
        if (!isCallerEmployeeOrAdmin()) {
            Client client = getAuthenticatedClient();
            if (card.getClient() == null || !card.getClient().getId().equals(client.getId())) {
                throw new RuntimeException("Nemate pristup ovoj kartici");
            }
        }

        if (card.getCardCategory() != CardCategory.INTERNET_PREPAID) {
            throw new IllegalStateException("Povlacenje je dostupno samo za INTERNET_PREPAID kartice.");
        }
        if (card.getPrepaidBalance() == null || card.getPrepaidBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Nedovoljno sredstava na kartici: dostupno " + card.getPrepaidBalance());
        }

        // BE-ACC-03: pessimistic lock na targetAccount za simetricnost sa
        // top-up putanjom — sprecava lost-update race kad paralelni withdraw-i
        // pisu na isti racun (read→add→write je non-atomic bez lock-a).
        Account targetAccount = accountRepository.findForUpdateById(targetAccountId)
                .orElseThrow(() -> new RuntimeException("Racun nije pronadjen"));
        if (targetAccount.getClient() == null
                || !targetAccount.getClient().getId().equals(card.getClient().getId())) {
            throw new RuntimeException("Ciljni racun mora pripadati vlasniku kartice.");
        }

        card.setPrepaidBalance(card.getPrepaidBalance().subtract(amount));
        cardRepository.save(card);

        targetAccount.setBalance(targetAccount.getBalance().add(amount));
        targetAccount.setAvailableBalance(targetAccount.getAvailableBalance().add(amount));
        accountRepository.save(targetAccount);

        CardResponseDto response = toResponse(card);
        recordIdempotencyMarker(dedupKey, "card-withdraw");
        return response;
    }

    /**
     * Gradi dedup kljuc {@code <prefix>-<cardId>-<clientKey>} ili {@code null}
     * kad klijent ne posalje Idempotency-Key (tada se replay-guard preskace i
     * ponasanje je identicno staroj putanji).
     */
    private String normalizeIdempotencyKey(String prefix, Long cardId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        // Vezujemo kljuc za (prefix, cardId) da isti klijent-generisani kljuc ne
        // moze slucajno da kolidira preko razlicitih kartica/operacija. Trim na
        // sirinu internal_requests.idempotency_key kolone (100).
        String composed = prefix + "-" + cardId + "-" + idempotencyKey.trim();
        return composed.length() > 100 ? composed.substring(0, 100) : composed;
    }

    /**
     * Upisuje dedup marker u istoj transakciji kao novcana noga — UNIQUE
     * constraint cini "obradi jednom" atomicnim: konkurentni replay koji prodje
     * lock dobija {@code DataIntegrityViolationException} (propagira → caller
     * dobija gresku, ne dupli transfer). No-op kad kljuc nije prosledjen.
     */
    private void recordIdempotencyMarker(String dedupKey, String endpoint) {
        if (dedupKey != null && internalIdempotencyService != null) {
            internalIdempotencyService.store(dedupKey, endpoint, 200, "OK");
        }
    }

    /**
     * R1 317: prebacuje istekle (ne-DEACTIVATED) kartice u DEACTIVATED. Vidi
     * {@link CardService#expireDueCards(LocalDate)}. Idempotentno — kartica koja
     * je vec DEACTIVATED se ne pokupi ({@code findExpiredWithStatusNot} iskljucuje
     * DEACTIVATED), pa ponovljeno pokretanje istog dana ne radi nista.
     */
    @Override
    @Transactional
    public int expireDueCards(LocalDate asOf) {
        LocalDate cutoff = asOf != null ? asOf : LocalDate.now();
        List<Card> expired = cardRepository.findExpiredWithStatusNot(cutoff, CardStatus.DEACTIVATED);
        if (expired.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Card card : expired) {
            CardStatus previous = card.getStatus();
            card.setStatus(CardStatus.DEACTIVATED);
            cardRepository.save(card);
            count++;

            Long ownerId = card.getClient() != null ? card.getClient().getId() : null;
            // Audit (best-effort) — istekla kartica je sistemska deaktivacija
            // (aktor = SYSTEM/scheduler, nema SecurityContext).
            recordAuditSafe(0L, "SYSTEM", AuditActionType.CARD_DEACTIVATED,
                    "Card " + card.getId() + " auto-deactivated (expired " + card.getExpirationDate()
                            + ", was " + previous + ", owner client=" + ownerId + ")",
                    "CARD", card.getId());

            // Best-effort obavestenje vlasniku.
            if (card.getClient() != null) {
                try {
                    String last4 = card.getCardNumber().length() >= 4
                            ? card.getCardNumber().substring(card.getCardNumber().length() - 4)
                            : card.getCardNumber();
                    notificationService.notify(
                            card.getClient().getId(), "CLIENT",
                            NotificationType.CARD_BLOCKED,
                            "Kartica istekla",
                            "Vaša kartica koja se završava na " + last4 + " je istekla i deaktivirana je.",
                            "CARD", card.getId());
                } catch (Exception e) {
                    log.warn("Card expiry notify fail (best-effort) card={}: {}", card.getId(), e.getMessage());
                }
            }
        }
        log.info("Card expiry: {} istekle kartice prebacene u DEACTIVATED (cutoff={})", count, cutoff);
        return count;
    }

    private void requireAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new RuntimeException("Klijent nije pronadjen");
        }
    }

    private boolean isCallerEmployeeOrAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) return false;
        return auth.getAuthorities().stream().anyMatch(a -> {
            String role = a.getAuthority();
            return "ROLE_ADMIN".equals(role) || "ROLE_EMPLOYEE".equals(role)
                    || "ADMIN".equals(role) || "EMPLOYEE".equals(role)
                    || "SUPERVISOR".equals(role);
        });
    }

    /**
     * P0-B8 N3: privilege-escalation guard. Baca {@link AccessDeniedException}
     * ako je trenutno autentifikovani pozivalac CLIENT. Zaposleni
     * (ROLE_EMPLOYEE / ROLE_ADMIN / SUPERVISOR) i nezasticeni interni pozivi
     * (bez autentikacije — unit testovi) prolaze. Zatvara agentic put kojim je
     * klijent kroz Arbitro agenta mogao da odblokira karticu, bez promene
     * legitimnog HTTP employee puta.
     */
    private void assertNotClientCaller(String message) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) return; // interni/no-auth — ne diramo
        boolean isClient = auth.getAuthorities().stream().anyMatch(a -> {
            String role = a.getAuthority();
            return "ROLE_CLIENT".equals(role) || "CLIENT".equals(role);
        });
        if (isClient && !isCallerEmployeeOrAdmin()) {
            throw new AccessDeniedException(message);
        }
    }
}
