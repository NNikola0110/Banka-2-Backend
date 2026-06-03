package rs.raf.banka2_bek.card.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountType;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.card.dto.CardResponseDto;
import rs.raf.banka2_bek.card.dto.CreateCardRequestDto;
import rs.raf.banka2_bek.card.model.Card;
import rs.raf.banka2_bek.card.model.CardStatus;
import rs.raf.banka2_bek.card.model.CardType;
import rs.raf.banka2_bek.card.repository.CardRepository;
import rs.raf.banka2_bek.card.service.implementation.CardServiceImpl;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.internalapi.model.InternalRequest;
import rs.raf.banka2_bek.internalapi.service.InternalIdempotencyService;
import rs.raf.banka2_bek.notification.NotificationPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {

    @Mock private CardRepository cardRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private NotificationPublisher notificationPublisher;
    @Mock private InternalIdempotencyService internalIdempotencyService;
    @Mock private rs.raf.banka2_bek.audit.service.AuditLogService auditLogService;
    @Mock private rs.raf.banka2_bek.audit.service.CurrentAuditActorResolver currentAuditActorResolver;

    @InjectMocks
    private CardServiceImpl cardService;

    private Client client;
    private Account personalAccount;
    private Account businessAccount;

    @BeforeEach
    void setUp() {
        // Test isolation guard: clear leaked SecurityContextHolder state from prior test
        // classes (some test files set principal but don't clear, causing NPE
        // `principal is null` in getOptionalClient when CardServiceImpl runs in full
        // suite). Defense-in-depth — mockAuth() sets context per-test; this clears.
        SecurityContextHolder.clearContext();

        client = Client.builder()
                .id(1L).firstName("Stefan").lastName("Jovanovic")
                .email("stefan@test.com").build();

        personalAccount = Account.builder()
                .id(1L).accountNumber("222000112345678911")
                .accountType(AccountType.CHECKING)
                .client(client).build();

        businessAccount = Account.builder()
                .id(2L).accountNumber("222000112345678912")
                .accountType(AccountType.BUSINESS)
                .client(client).build();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // Ensure we don't leak context to other test classes.
        SecurityContextHolder.clearContext();
    }

    private void mockAuth(String email) {
        UserDetails userDetails = User.builder()
                .username(email).password("pass").authorities("ROLE_CLIENT").build();
        Authentication auth = mock(Authentication.class);
        // SC28/T2-007 fix (14.05.2026): createCard sad prvo proverava isCallerEmployeeOrAdmin()
        // koja cita auth.getAuthorities(). Stubovi getPrincipal/getAuthentication su lenient
        // jer ih neki test putevi (npr. accountNotFound) ne zovu.
        org.mockito.Mockito.lenient().when(auth.getPrincipal()).thenReturn(userDetails);
        java.util.Collection<org.springframework.security.core.GrantedAuthority> auths =
                java.util.Collections.singletonList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CLIENT"));
        org.mockito.Mockito.lenient().when(((Authentication) auth).getAuthorities()).thenAnswer(inv -> auths);
        SecurityContext ctx = mock(SecurityContext.class);
        org.mockito.Mockito.lenient().when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
    }

    /**
     * P0-B8 N3: autentifikuje kao zaposleni sa zadatim authorities (npr.
     * ROLE_EMPLOYEE, ROLE_ADMIN) za testove legitimnog employee puta.
     */
    private void mockAuthWithAuthorities(String email, String... authorities) {
        UserDetails userDetails = User.builder()
                .username(email).password("pass").authorities(authorities).build();
        Authentication auth = mock(Authentication.class);
        org.mockito.Mockito.lenient().when(auth.getPrincipal()).thenReturn(userDetails);
        java.util.Collection<org.springframework.security.core.GrantedAuthority> auths =
                java.util.Arrays.stream(authorities)
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .map(a -> (org.springframework.security.core.GrantedAuthority) a)
                        .toList();
        org.mockito.Mockito.lenient().when(auth.getAuthorities()).thenAnswer(inv -> auths);
        SecurityContext ctx = mock(SecurityContext.class);
        org.mockito.Mockito.lenient().when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
    }

    @Nested
    @DisplayName("createCard")
    class CreateCard {

        @Test
        @DisplayName("kreira karticu za licni racun")
        void successPersonal() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(1L);
                return c;
            });

            CreateCardRequestDto dto = new CreateCardRequestDto();
            dto.setAccountId(1L);
            dto.setCardLimit(BigDecimal.valueOf(50000));

            CardResponseDto result = cardService.createCard(dto);

            assertThat(result).isNotNull();
            assertThat(result.getCardNumber()).hasSize(16);
            assertThat(result.getStatus()).isEqualTo(CardStatus.ACTIVE);
            assertThat(result.getCardLimit()).isEqualByComparingTo("50000");
        }

        @Test
        @DisplayName("baca gresku kad je dostignut max 2 kartice za licni racun")
        void maxCardsPersonal() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(2L);

            CreateCardRequestDto dto = new CreateCardRequestDto();
            dto.setAccountId(1L);

            assertThatThrownBy(() -> cardService.createCard(dto))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("maksimalan broj kartica");
        }

        @Test
        @DisplayName("baca gresku kad ovlasceno lice vec ima karticu za poslovni racun")
        void maxCardsBusiness() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(2L)).thenReturn(Optional.of(businessAccount));
            when(cardRepository.countByAccountIdAndClientIdAndStatusNot(2L, 1L, CardStatus.DEACTIVATED)).thenReturn(1L);

            CreateCardRequestDto dto = new CreateCardRequestDto();
            dto.setAccountId(2L);

            assertThatThrownBy(() -> cardService.createCard(dto))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("vec ima karticu za ovaj poslovni racun");
        }

        @Test
        @DisplayName("default limit je 100000 kad nije zadat")
        void defaultLimit() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(1L);
                return c;
            });

            CreateCardRequestDto dto = new CreateCardRequestDto();
            dto.setAccountId(1L);

            CardResponseDto result = cardService.createCard(dto);
            assertThat(result.getCardLimit()).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("BE-ACC-01 (PCI-DSS): createCard NIKAD ne vraca raw CVV u response-u")
        void createCardNeverLeaksCvv() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(1L);
                // simuliramo da entity ima plaintext CVV (kao realan flow) —
                // DTO ipak mora da vrati null.
                return c;
            });

            CreateCardRequestDto dto = new CreateCardRequestDto();
            dto.setAccountId(1L);

            CardResponseDto result = cardService.createCard(dto);
            assertThat(result.getCvv()).as("CVV NIKAD ne sme da napusti BE u response-u (PCI-DSS)").isNull();
        }

        /**
         * R1-500-CVV (PCI-DSS Req 3.2) — ZATVORENO 03.06: CVV se VISE NE CUVA
         * at-rest. Ranije karakterizacioni test je "pinovao" plaintext CVV u
         * {@code cards.cvv} koloni; sada je flip-ovan da DOKAZE suprotno —
         * entitet koji ide u perzistenciju ne nosi nikakvu CVV vrednost
         * ({@code Card.cvv} je {@code @Transient} i {@code createAndSaveCard} ga
         * vise ne generise/postavlja).
         *
         * <p>CVV se nigde u codebase-u ne CITA radi verifikacije (jedini callovi
         * {@code getCvv()} bili su test-asercije da DTO vraca {@code null}), pa je
         * PCI-ispravan fix da se ne perzistuje uopste — umesto hash-at-rest, koji
         * bi i dalje predstavljao zabranjeno cuvanje CVV-a posle autorizacije.
         */
        @Test
        @DisplayName("R1-500-CVV (PCI-DSS): CVV se NE cuva at-rest — entitet koji se perzistuje nema CVV")
        void cvvNotPersistedAtRest() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            org.mockito.ArgumentCaptor<Card> saved = org.mockito.ArgumentCaptor.forClass(Card.class);
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(1L);
                return c;
            });

            CreateCardRequestDto dto = new CreateCardRequestDto();
            dto.setAccountId(1L);
            cardService.createCard(dto);

            verify(cardRepository).save(saved.capture());
            // PCI-DSS: vrednost koja ide u perzistenciju ne sme nositi plaintext CVV.
            // Posto je polje @Transient i createAndSaveCard ga ne postavlja, ostaje null.
            assertThat(saved.getValue().getCvv())
                    .as("CVV se vise NE cuva at-rest (PCI-DSS Req 3.2) — vidi R1-500-CVV")
                    .isNull();
        }

        /**
         * R1-500-CVV — strukturni dokaz da je {@code Card.cvv} {@code @Transient}
         * (Hibernate ga nikad ne mapira u kolonu). Ovo je drugi sloj garancije
         * pored {@link #cvvNotPersistedAtRest()}: cak i ako bi neko slucajno
         * pozvao {@code setCvv} pre save-a, polje ne moze da zavrsi u DB-u.
         */
        @Test
        @DisplayName("R1-500-CVV: Card.cvv je @Transient (Hibernate ga ne mapira u kolonu)")
        void cvvFieldIsTransient() throws NoSuchFieldException {
            java.lang.reflect.Field cvvField = Card.class.getDeclaredField("cvv");
            assertThat(cvvField.isAnnotationPresent(jakarta.persistence.Transient.class))
                    .as("Card.cvv mora biti @Transient da se CVV nikad ne perzistuje (PCI-DSS)")
                    .isTrue();
            assertThat(cvvField.isAnnotationPresent(jakarta.persistence.Column.class))
                    .as("Card.cvv ne sme imati @Column (ne sme se mapirati u DB kolonu)")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("getMyCards")
    class GetMyCards {

        @Test
        @DisplayName("vraca kartice za klijenta")
        void returnsCards() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));

            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .cardLimit(BigDecimal.valueOf(250000)).status(CardStatus.ACTIVE)
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findByClientId(1L)).thenReturn(List.of(card));

            List<CardResponseDto> result = cardService.getMyCards();
            assertThat(result).hasSize(1);
            // R1-638: maska po spec-u Celina 2 §321 — prve 4 + 8 zvezdica + zadnje 4 (bez razmaka).
            assertThat(result.get(0).getCardNumber()).isEqualTo("4222********7890");
            assertThat(result.get(0).getCvv()).isNull();
        }

        @Test
        @DisplayName("vraca praznu listu za non-client (admin)")
        void emptyForNonClient() {
            SecurityContext ctx = mock(SecurityContext.class);
            when(ctx.getAuthentication()).thenReturn(null);
            SecurityContextHolder.setContext(ctx);

            List<CardResponseDto> result = cardService.getMyCards();
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("blockCard / unblockCard / deactivateCard")
    class CardStatusChanges {

        @Test
        @DisplayName("blokira aktivnu karticu")
        void blockActive() {
            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .status(CardStatus.ACTIVE).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CardResponseDto result = cardService.blockCard(1L);
            assertThat(result.getStatus()).isEqualTo(CardStatus.BLOCKED);
        }

        @Test
        @DisplayName("ne moze da blokira deaktiviranu karticu")
        void cannotBlockDeactivated() {
            Card card = Card.builder().id(1L).status(CardStatus.DEACTIVATED).build();
            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));

            assertThatThrownBy(() -> cardService.blockCard(1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("ne moze blokirati");
        }

        @Test
        @DisplayName("odblokira blokiranu karticu")
        void unblockBlocked() {
            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .status(CardStatus.BLOCKED).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CardResponseDto result = cardService.unblockCard(1L);
            assertThat(result.getStatus()).isEqualTo(CardStatus.ACTIVE);
        }

        @Test
        @DisplayName("P0-B8 N3: CLIENT ne sme da odblokira karticu → AccessDeniedException")
        void unblockAsClient_denied() {
            mockAuth("stefan@test.com"); // ROLE_CLIENT

            assertThatThrownBy(() -> cardService.unblockCard(1L))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

            // Guard fire-uje PRE ucitavanja kartice — repo se ne dira.
            verify(cardRepository, never()).findById(any());
        }

        @Test
        @DisplayName("P0-B8 N3: EMPLOYEE sme da odblokira BILO KOJU karticu → prolazi")
        void unblockAsEmployee_allowed() {
            mockAuthWithAuthorities("zaposleni@banka.rs", "ROLE_EMPLOYEE");
            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .status(CardStatus.BLOCKED).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();
            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CardResponseDto result = cardService.unblockCard(1L);
            assertThat(result.getStatus()).isEqualTo(CardStatus.ACTIVE);
        }

        @Test
        @DisplayName("ne moze da odblokira aktivnu karticu")
        void cannotUnblockActive() {
            Card card = Card.builder().id(1L).status(CardStatus.ACTIVE).build();
            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));

            assertThatThrownBy(() -> cardService.unblockCard(1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("blokirana");
        }

        @Test
        @DisplayName("deaktivira karticu")
        void deactivateCard() {
            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .status(CardStatus.ACTIVE).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CardResponseDto result = cardService.deactivateCard(1L);
            assertThat(result.getStatus()).isEqualTo(CardStatus.DEACTIVATED);
        }

        @Test
        @DisplayName("R5 1891: blockCard audit aktor je IZVRSILAC (zaposleni), ne vlasnik kartice")
        void blockCard_auditActorIsExecutorNotOwner_R5_1891() {
            // Vlasnik kartice je klijent #1; izvrsilac je zaposleni #9.
            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .status(CardStatus.ACTIVE).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();
            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(currentAuditActorResolver.resolveCurrentActor())
                    .thenReturn(new rs.raf.banka2_bek.audit.service.CurrentAuditActorResolver.AuditActor(9L, "EMPLOYEE"));

            cardService.blockCard(1L);

            // Aktor = izvrsilac (9/EMPLOYEE), NE vlasnik kartice (1/CLIENT); target = kartica.
            verify(auditLogService).record(
                    eq(9L), eq("EMPLOYEE"),
                    eq(rs.raf.banka2_bek.audit.model.AuditActionType.CARD_BLOCKED),
                    anyString(), eq("CARD"), eq(1L));
        }

        @Test
        @DisplayName("R5 1890: deaktivacija kartice emituje CARD_DEACTIVATED audit")
        void deactivateCard_emitsAudit_R5_1890() {
            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .status(CardStatus.ACTIVE).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();
            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(currentAuditActorResolver.resolveCurrentActor())
                    .thenReturn(new rs.raf.banka2_bek.audit.service.CurrentAuditActorResolver.AuditActor(9L, "EMPLOYEE"));

            cardService.deactivateCard(1L);

            verify(auditLogService).record(
                    eq(9L), eq("EMPLOYEE"),
                    eq(rs.raf.banka2_bek.audit.model.AuditActionType.CARD_DEACTIVATED),
                    anyString(), eq("CARD"), eq(1L));
        }
    }

    @Nested
    @DisplayName("updateCardLimit")
    class UpdateLimit {

        @Test
        @DisplayName("menja limit aktivne kartice")
        void success() {
            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .status(CardStatus.ACTIVE).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CardResponseDto result = cardService.updateCardLimit(1L, BigDecimal.valueOf(200000));
            assertThat(result.getCardLimit()).isEqualByComparingTo("200000");
        }

        @Test
        @DisplayName("ne moze menjati limit deaktivirane kartice")
        void cannotChangeLimitOfDeactivated() {
            Card card = Card.builder().id(1L).status(CardStatus.DEACTIVATED).build();
            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));

            assertThatThrownBy(() -> cardService.updateCardLimit(1L, BigDecimal.valueOf(50000)))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("deaktivirane");
        }

        @Test
        @DisplayName("baca gresku kad kartica ne postoji")
        void notFound() {
            when(cardRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cardService.updateCardLimit(999L, BigDecimal.valueOf(50000)))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("nije pronadjena");
        }

        @Test
        @DisplayName("menja limit blokirane kartice (dozvoljen)")
        void canChangeLimitOfBlocked() {
            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .status(CardStatus.BLOCKED).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CardResponseDto result = cardService.updateCardLimit(1L, BigDecimal.valueOf(300000));
            assertThat(result.getCardLimit()).isEqualByComparingTo("300000");
        }
    }

    @Nested
    @DisplayName("createCardForAccount")
    class CreateCardForAccount {

        @Test
        @DisplayName("kreira karticu za racun sa default limitom i tipom")
        void successWithDefaults() {
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(1L);
                return c;
            });

            CardResponseDto result = cardService.createCardForAccount(1L, 1L, null, null);

            assertThat(result).isNotNull();
            assertThat(result.getCardLimit()).isEqualByComparingTo("100000");
            assertThat(result.getStatus()).isEqualTo(CardStatus.ACTIVE);
        }

        @Test
        @DisplayName("kreira karticu sa zadatim limitom i MasterCard tipom")
        void successWithCustomLimitAndType() {
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(2L);
                return c;
            });

            CardResponseDto result = cardService.createCardForAccount(1L, 1L, BigDecimal.valueOf(250000), CardType.MASTERCARD);

            assertThat(result).isNotNull();
            assertThat(result.getCardLimit()).isEqualByComparingTo("250000");
            assertThat(result.getCardName()).isEqualTo("MasterCard Debit");
        }

        @Test
        @DisplayName("baca gresku kad racun ne postoji")
        void accountNotFound() {
            when(accountRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cardService.createCardForAccount(999L, 1L, null, null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Racun nije pronadjen");
        }

        @Test
        @DisplayName("baca gresku kad klijent ne postoji")
        void clientNotFound() {
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(clientRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cardService.createCardForAccount(1L, 999L, null, null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Klijent nije pronadjen");
        }

        @Test
        @DisplayName("baca gresku kad je dostignut limit kartica")
        void maxCardsReached() {
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(2L);

            assertThatThrownBy(() -> cardService.createCardForAccount(1L, 1L, null, null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("maksimalan broj kartica");
        }

        @Test
        @DisplayName("kreira DinaCard karticu")
        void dinacardType() {
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(3L);
                return c;
            });

            CardResponseDto result = cardService.createCardForAccount(1L, 1L, null, CardType.DINACARD);
            assertThat(result.getCardName()).isEqualTo("DinaCard Debit");
        }

        @Test
        @DisplayName("kreira American Express karticu")
        void americanExpressType() {
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(4L);
                return c;
            });

            CardResponseDto result = cardService.createCardForAccount(1L, 1L, null, CardType.AMERICAN_EXPRESS);
            assertThat(result.getCardName()).isEqualTo("American Express");
        }
    }

    @Nested
    @DisplayName("getCardsByAccount")
    class GetCardsByAccount {

        @Test
        @DisplayName("vraca kartice za racun")
        void returnsCards() {
            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .cardLimit(BigDecimal.valueOf(250000)).status(CardStatus.ACTIVE)
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findByAccountId(1L)).thenReturn(List.of(card));

            List<CardResponseDto> result = cardService.getCardsByAccount(1L);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getCvv()).isNull(); // masked response
        }

        @Test
        @DisplayName("vraca praznu listu za racun bez kartica")
        void emptyList() {
            when(cardRepository.findByAccountId(999L)).thenReturn(List.of());
            assertThat(cardService.getCardsByAccount(999L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("blockCard / unblockCard / deactivateCard - dodatni slucajevi")
    class AdditionalStatusChanges {

        @Test
        @DisplayName("blockCard - baca gresku kad kartica ne postoji")
        void blockCardNotFound() {
            when(cardRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cardService.blockCard(999L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("nije pronadjena");
        }

        @Test
        @DisplayName("blockCard - baca gresku kad je kartica vec blokirana")
        void blockAlreadyBlocked() {
            Card card = Card.builder().id(1L).status(CardStatus.BLOCKED).build();
            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));

            assertThatThrownBy(() -> cardService.blockCard(1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("vec blokirana");
        }

        @Test
        @DisplayName("blockCard - proverava vlasnistvo kartice")
        void blockCardOwnershipCheck() {
            mockAuth("stefan@test.com");

            Client otherClient = Client.builder()
                    .id(99L).firstName("Drugi").lastName("Klijent")
                    .email("drugi@test.com").build();

            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(otherClient)
                    .status(CardStatus.ACTIVE).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));

            assertThatThrownBy(() -> cardService.blockCard(1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Nemate pristup");
        }

        @Test
        @DisplayName("blockCard - salje email notifikaciju")
        void blockCardSendsEmail() {
            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .status(CardStatus.ACTIVE).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            cardService.blockCard(1L);

            verify(notificationPublisher).sendCardBlockedMail(
                    eq("stefan@test.com"), eq("7890"), any(LocalDate.class));
        }

        @Test
        @DisplayName("blockCard - email failure ne sprecava blokiranje")
        void blockCardEmailFailureIgnored() {
            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .status(CardStatus.ACTIVE).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new RuntimeException("SMTP error")).when(notificationPublisher)
                    .sendCardBlockedMail(anyString(), anyString(), any(LocalDate.class));

            CardResponseDto result = cardService.blockCard(1L);
            assertThat(result.getStatus()).isEqualTo(CardStatus.BLOCKED);
        }

        @Test
        @DisplayName("unblockCard - baca gresku kad kartica ne postoji")
        void unblockCardNotFound() {
            when(cardRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cardService.unblockCard(999L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("nije pronadjena");
        }

        @Test
        @DisplayName("unblockCard - ne moze odblokirati deaktiviranu karticu")
        void cannotUnblockDeactivated() {
            Card card = Card.builder().id(1L).status(CardStatus.DEACTIVATED).build();
            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));

            assertThatThrownBy(() -> cardService.unblockCard(1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("blokirana");
        }

        @Test
        @DisplayName("unblockCard - salje email notifikaciju")
        void unblockCardSendsEmail() {
            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .status(CardStatus.BLOCKED).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            cardService.unblockCard(1L);

            verify(notificationPublisher).sendCardUnblockedMail(
                    eq("stefan@test.com"), eq("7890"));
        }

        @Test
        @DisplayName("unblockCard - email failure ne sprecava deblokiranje")
        void unblockCardEmailFailureIgnored() {
            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .status(CardStatus.BLOCKED).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new RuntimeException("SMTP error")).when(notificationPublisher)
                    .sendCardUnblockedMail(anyString(), anyString());

            CardResponseDto result = cardService.unblockCard(1L);
            assertThat(result.getStatus()).isEqualTo(CardStatus.ACTIVE);
        }

        @Test
        @DisplayName("deactivateCard - baca gresku kad kartica ne postoji")
        void deactivateCardNotFound() {
            when(cardRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cardService.deactivateCard(999L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("nije pronadjena");
        }

        @Test
        @DisplayName("deactivateCard - baca gresku kad je vec deaktivirana")
        void deactivateAlreadyDeactivated() {
            Card card = Card.builder().id(1L).status(CardStatus.DEACTIVATED).build();
            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));

            assertThatThrownBy(() -> cardService.deactivateCard(1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("vec deaktivirana");
        }

        @Test
        @DisplayName("deactivateCard - moze deaktivirati blokiranu karticu")
        void deactivateBlockedCard() {
            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .status(CardStatus.BLOCKED).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CardResponseDto result = cardService.deactivateCard(1L);
            assertThat(result.getStatus()).isEqualTo(CardStatus.DEACTIVATED);
        }

        // ══════════════════════════════════════════════════════════════
        //  C2 Sc32 — Pokusaj aktivacije deaktivirane kartice (literal poruka)
        // ══════════════════════════════════════════════════════════════

        @Test
        @DisplayName("activateCard Sc32 - deaktivirana kartica baca literal spec poruku")
        void activateDeactivatedCard_rejected_Sc32() {
            Card card = Card.builder().id(1L).status(CardStatus.DEACTIVATED).build();
            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));

            assertThatThrownBy(() -> cardService.activateCard(1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Kartica je deaktivirana i ne može se ponovo aktivirati");

            // Status se NE menja — invarijanta drzi (nema DEACTIVATED→ACTIVE puta).
            verify(cardRepository, never()).save(any(Card.class));
        }

        @Test
        @DisplayName("activateCard Sc32 - blokirana kartica se ne aktivira ovim putem")
        void activateBlockedCard_rejected_Sc32() {
            Card card = Card.builder().id(1L).status(CardStatus.BLOCKED).build();
            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));

            assertThatThrownBy(() -> cardService.activateCard(1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("zaposleni");

            verify(cardRepository, never()).save(any(Card.class));
        }

        @Test
        @DisplayName("activateCard Sc32 - vec aktivna kartica je idempotentna (vraca ACTIVE)")
        void activateAlreadyActiveCard_idempotent_Sc32() {
            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .status(CardStatus.ACTIVE).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();
            when(cardRepository.findById(1L)).thenReturn(Optional.of(card));

            CardResponseDto result = cardService.activateCard(1L);
            assertThat(result.getStatus()).isEqualTo(CardStatus.ACTIVE);
            verify(cardRepository, never()).save(any(Card.class));
        }

        @Test
        @DisplayName("activateCard Sc32 - nepostojeca kartica baca gresku")
        void activateCardNotFound_Sc32() {
            when(cardRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cardService.activateCard(999L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("nije pronadjena");
        }
    }

    @Nested
    @DisplayName("createCard - dodatni slucajevi")
    class CreateCardAdditional {

        @Test
        @DisplayName("baca gresku kad racun ne postoji")
        void accountNotFound() {
            mockAuth("stefan@test.com");
            // clientRepository.findByEmail stub je lenient — accountNotFound test
            // baca gresku PRE getAuthenticatedClient() poziva (sad se accountRepository.findById
            // poziva prvi posle 14.05 SC28 fix-a).
            org.mockito.Mockito.lenient().when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(999L)).thenReturn(Optional.empty());

            CreateCardRequestDto dto = new CreateCardRequestDto();
            dto.setAccountId(999L);

            assertThatThrownBy(() -> cardService.createCard(dto))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Racun nije pronadjen");
        }

        @Test
        @DisplayName("baca gresku kad klijent nije vlasnik racuna")
        void notAccountOwner() {
            Client otherClient = Client.builder()
                    .id(99L).firstName("Drugi").lastName("K")
                    .email("drugi@test.com").build();

            Account otherAccount = Account.builder()
                    .id(3L).accountNumber("222000112345678913")
                    .accountType(AccountType.CHECKING)
                    .client(otherClient).build();

            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(3L)).thenReturn(Optional.of(otherAccount));

            CreateCardRequestDto dto = new CreateCardRequestDto();
            dto.setAccountId(3L);

            assertThatThrownBy(() -> cardService.createCard(dto))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Nemate pristup");
        }

        @Test
        @DisplayName("baca gresku kad klijent nije autentifikovan")
        void notAuthenticated() {
            SecurityContext ctx = mock(SecurityContext.class);
            when(ctx.getAuthentication()).thenReturn(null);
            SecurityContextHolder.setContext(ctx);

            CreateCardRequestDto dto = new CreateCardRequestDto();
            dto.setAccountId(1L);

            assertThatThrownBy(() -> cardService.createCard(dto))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Klijent nije pronadjen");
        }

        @Test
        @DisplayName("kreira karticu sa MasterCard tipom")
        void createMastercard() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(1L);
                return c;
            });

            CreateCardRequestDto dto = new CreateCardRequestDto();
            dto.setAccountId(1L);
            dto.setCardType(CardType.MASTERCARD);

            CardResponseDto result = cardService.createCard(dto);
            assertThat(result.getCardName()).isEqualTo("MasterCard Debit");
        }

        @Test
        @DisplayName("kreira drugu karticu za licni racun (1 od 2)")
        void createSecondCardForPersonal() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(1L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(2L);
                return c;
            });

            CreateCardRequestDto dto = new CreateCardRequestDto();
            dto.setAccountId(1L);

            CardResponseDto result = cardService.createCard(dto);
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(CardStatus.ACTIVE);
        }

        @Test
        @DisplayName("racun bez klijenta - baca gresku o pristupu")
        void accountWithoutClient() {
            Account noClientAccount = Account.builder()
                    .id(5L).accountNumber("222000112345678915")
                    .accountType(AccountType.BUSINESS)
                    .client(null).build();

            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(5L)).thenReturn(Optional.of(noClientAccount));

            CreateCardRequestDto dto = new CreateCardRequestDto();
            dto.setAccountId(5L);

            assertThatThrownBy(() -> cardService.createCard(dto))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Nemate pristup");
        }
    }

    @Nested
    @DisplayName("maskCardNumber i getMyCards")
    class MaskCardNumber {

        @Test
        @DisplayName("getMyCards maskira broj kartice i cvv")
        void masksCardData() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));

            Card card = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .cardLimit(BigDecimal.valueOf(250000)).status(CardStatus.ACTIVE)
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findByClientId(1L)).thenReturn(List.of(card));

            List<CardResponseDto> result = cardService.getMyCards();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getCardNumber()).startsWith("4222");
            assertThat(result.get(0).getCardNumber()).endsWith("7890");
            assertThat(result.get(0).getCardNumber()).contains("****");
            assertThat(result.get(0).getCvv()).isNull();
        }

        @Test
        @DisplayName("getCardsByAccount maskira kartice za vise kartica")
        void masksMultipleCards() {
            Card card1 = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .cardLimit(BigDecimal.valueOf(100000)).status(CardStatus.ACTIVE)
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            Card card2 = Card.builder()
                    .id(2L).cardNumber("5333009876543210").cardName("MasterCard Debit")
                    .cvv("456").account(personalAccount).client(client)
                    .cardLimit(BigDecimal.valueOf(200000)).status(CardStatus.BLOCKED)
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findByAccountId(1L)).thenReturn(List.of(card1, card2));

            List<CardResponseDto> result = cardService.getCardsByAccount(1L);
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getCvv()).isNull();
            assertThat(result.get(1).getCvv()).isNull();
            assertThat(result.get(0).getCardNumber()).contains("****");
            assertThat(result.get(1).getCardNumber()).contains("****");
        }
    }

    @Nested
    @DisplayName("CardType generation paths")
    class CardTypeGeneration {

        @Test
        @DisplayName("createCard resolves VISA card name")
        void visaCardName() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(10L);
                return c;
            });

            CreateCardRequestDto req = new CreateCardRequestDto();
            req.setAccountId(1L);
            req.setCardType(CardType.VISA);
            req.setCardLimit(BigDecimal.valueOf(50000));

            CardResponseDto result = cardService.createCard(req);
            assertThat(result.getCardName()).isEqualTo("Visa Debit");
            assertThat(result.getCardType()).isEqualTo(CardType.VISA);
        }

        @Test
        @DisplayName("createCard resolves MASTERCARD card name")
        void mastercardCardName() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(11L);
                return c;
            });

            CreateCardRequestDto req = new CreateCardRequestDto();
            req.setAccountId(1L);
            req.setCardType(CardType.MASTERCARD);
            req.setCardLimit(BigDecimal.valueOf(50000));

            CardResponseDto result = cardService.createCard(req);
            assertThat(result.getCardName()).isEqualTo("MasterCard Debit");
        }

        @Test
        @DisplayName("createCard resolves DINACARD card name")
        void dinacardCardName() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(12L);
                return c;
            });

            CreateCardRequestDto req = new CreateCardRequestDto();
            req.setAccountId(1L);
            req.setCardType(CardType.DINACARD);
            req.setCardLimit(BigDecimal.valueOf(50000));

            CardResponseDto result = cardService.createCard(req);
            assertThat(result.getCardName()).isEqualTo("DinaCard Debit");
        }

        @Test
        @DisplayName("createCard resolves AMERICAN_EXPRESS card name")
        void amexCardName() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(13L);
                return c;
            });

            CreateCardRequestDto req = new CreateCardRequestDto();
            req.setAccountId(1L);
            req.setCardType(CardType.AMERICAN_EXPRESS);
            req.setCardLimit(BigDecimal.valueOf(50000));

            CardResponseDto result = cardService.createCard(req);
            assertThat(result.getCardName()).isEqualTo("American Express");
        }

        @Test
        @DisplayName("createCard with null cardType defaults to VISA")
        void nullCardTypeDefaultsToVisa() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(14L);
                return c;
            });

            CreateCardRequestDto req = new CreateCardRequestDto();
            req.setAccountId(1L);
            req.setCardType(null);
            req.setCardLimit(BigDecimal.valueOf(50000));

            CardResponseDto result = cardService.createCard(req);
            assertThat(result.getCardType()).isEqualTo(CardType.VISA);
        }

        @Test
        @DisplayName("createCard with null cardLimit defaults to 100000")
        void nullCardLimitDefaults() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(15L);
                return c;
            });

            CreateCardRequestDto req = new CreateCardRequestDto();
            req.setAccountId(1L);
            req.setCardType(CardType.VISA);
            req.setCardLimit(null);

            CardResponseDto result = cardService.createCard(req);
            assertThat(result.getCardLimit()).isEqualByComparingTo("100000");
        }
    }

    @Nested
    @DisplayName("Card limit enforcement for account types")
    class CardLimitEnforcement {

        @Test
        @DisplayName("business account allows 1 card per person - succeeds when 0 active")
        void businessAllowsOneCard() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(2L)).thenReturn(Optional.of(businessAccount));
            when(cardRepository.countByAccountIdAndClientIdAndStatusNot(2L, 1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(20L);
                return c;
            });

            CreateCardRequestDto req = new CreateCardRequestDto();
            req.setAccountId(2L);
            req.setCardType(CardType.VISA);
            req.setCardLimit(BigDecimal.valueOf(100000));

            CardResponseDto result = cardService.createCard(req);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("business account rejects second card for same person")
        void businessRejectsSecondCard() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(2L)).thenReturn(Optional.of(businessAccount));
            when(cardRepository.countByAccountIdAndClientIdAndStatusNot(2L, 1L, CardStatus.DEACTIVATED)).thenReturn(1L);

            CreateCardRequestDto req = new CreateCardRequestDto();
            req.setAccountId(2L);
            req.setCardType(CardType.VISA);

            assertThatThrownBy(() -> cardService.createCard(req))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("vec ima karticu");
        }

        @Test
        @DisplayName("personal account allows up to 2 cards")
        void personalAllowsTwoCards() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(1L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(21L);
                return c;
            });

            CreateCardRequestDto req = new CreateCardRequestDto();
            req.setAccountId(1L);
            req.setCardType(CardType.MASTERCARD);
            req.setCardLimit(BigDecimal.valueOf(150000));

            CardResponseDto result = cardService.createCard(req);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("personal account rejects third card")
        void personalRejectsThirdCard() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(2L);

            CreateCardRequestDto req = new CreateCardRequestDto();
            req.setAccountId(1L);
            req.setCardType(CardType.VISA);

            assertThatThrownBy(() -> cardService.createCard(req))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("maksimalan broj");
        }
    }

    @Nested
    @DisplayName("createCardForAccount - all CardType and default branches")
    class CreateCardForAccountAllTypes {

        @Test
        @DisplayName("createCardForAccount with null limit and null type defaults")
        void nullLimitAndType() {
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(30L);
                return c;
            });

            CardResponseDto result = cardService.createCardForAccount(1L, 1L, null, null);
            assertThat(result.getCardType()).isEqualTo(CardType.VISA);
            assertThat(result.getCardLimit()).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("createCardForAccount with explicit limit and DINACARD type")
        void explicitLimitAndDinacard() {
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            when(cardRepository.findByCardNumber(anyString())).thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(31L);
                return c;
            });

            CardResponseDto result = cardService.createCardForAccount(1L, 1L, BigDecimal.valueOf(75000), CardType.DINACARD);
            assertThat(result.getCardType()).isEqualTo(CardType.DINACARD);
            assertThat(result.getCardLimit()).isEqualByComparingTo("75000");
        }
    }

    @Nested
    @DisplayName("getOptionalClient - principal type branches")
    class GetOptionalClientBranches {

        @Test
        @DisplayName("String principal resolves to email")
        void stringPrincipal() {
            Authentication auth = mock(Authentication.class);
            when(auth.getPrincipal()).thenReturn("stefan@test.com");
            SecurityContext ctx = mock(SecurityContext.class);
            when(ctx.getAuthentication()).thenReturn(auth);
            SecurityContextHolder.setContext(ctx);
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));

            List<CardResponseDto> result = cardService.getMyCards();
            // Just verify it doesn't throw - string principal is handled
            verify(clientRepository).findByEmail("stefan@test.com");
        }

        @Test
        @DisplayName("null authentication returns empty list for getMyCards")
        void nullAuth() {
            SecurityContext ctx = mock(SecurityContext.class);
            when(ctx.getAuthentication()).thenReturn(null);
            SecurityContextHolder.setContext(ctx);

            List<CardResponseDto> result = cardService.getMyCards();
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("getAuthenticatedClient throws when no client found")
        void noClientThrows() {
            mockAuth("ghost@test.com");
            when(clientRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cardService.createCard(new CreateCardRequestDto()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Klijent nije pronadjen");
        }
    }

    @Nested
    @DisplayName("maskCardNumber edge cases")
    class MaskCardNumberEdgeCases {

        @Test
        @DisplayName("null card number returns null")
        void nullCardNumber() {
            Card card = Card.builder()
                    .id(1L).cardNumber(null).cardName("Test")
                    .cvv("123").account(personalAccount).client(client)
                    .cardType(CardType.VISA)
                    .cardLimit(BigDecimal.valueOf(100000)).status(CardStatus.ACTIVE)
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findByAccountId(1L)).thenReturn(List.of(card));

            List<CardResponseDto> result = cardService.getCardsByAccount(1L);
            assertThat(result.get(0).getCardNumber()).isNull();
        }

        @Test
        @DisplayName("short card number returned as-is")
        void shortCardNumber() {
            Card card = Card.builder()
                    .id(1L).cardNumber("1234567").cardName("Test")
                    .cvv("123").account(personalAccount).client(client)
                    .cardType(CardType.VISA)
                    .cardLimit(BigDecimal.valueOf(100000)).status(CardStatus.ACTIVE)
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();

            when(cardRepository.findByAccountId(1L)).thenReturn(List.of(card));

            List<CardResponseDto> result = cardService.getCardsByAccount(1L);
            assertThat(result.get(0).getCardNumber()).isEqualTo("1234567");
        }
    }

    @Nested
    @DisplayName("Card number generation retry")
    class CardNumberRetry {

        @Test
        @DisplayName("retries when card number already exists")
        void retriesOnDuplicate() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(personalAccount));
            when(cardRepository.countByAccountIdAndStatusNot(1L, CardStatus.DEACTIVATED)).thenReturn(0L);
            // First call returns existing, second returns empty
            when(cardRepository.findByCardNumber(anyString()))
                    .thenReturn(Optional.of(Card.builder().build()))
                    .thenReturn(Optional.empty());
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setId(40L);
                return c;
            });

            CreateCardRequestDto req = new CreateCardRequestDto();
            req.setAccountId(1L);
            req.setCardType(CardType.VISA);
            req.setCardLimit(BigDecimal.valueOf(50000));

            CardResponseDto result = cardService.createCard(req);
            assertThat(result).isNotNull();
            verify(cardRepository, times(2)).findByCardNumber(anyString());
        }
    }

    @Nested
    @DisplayName("Prepaid top-up / withdraw — P2-2 lost-update fix")
    class PrepaidBalanceLocking {

        private Card prepaidCard() {
            return Card.builder()
                    .id(50L)
                    .cardNumber("4222009999999999")
                    .cardName("Visa Internet Prepaid")
                    .cvv("321")
                    .account(personalAccount)
                    .client(client)
                    .status(CardStatus.ACTIVE)
                    .cardCategory(rs.raf.banka2_bek.card.model.CardCategory.INTERNET_PREPAID)
                    .prepaidBalance(new BigDecimal("0.00"))
                    .cardLimit(BigDecimal.ZERO)
                    .createdAt(LocalDate.now())
                    .expirationDate(LocalDate.now().plusYears(4))
                    .build();
        }

        private Account fundedAccount(Long id, String number, BigDecimal balance) {
            return Account.builder()
                    .id(id).accountNumber(number)
                    .accountType(AccountType.CHECKING)
                    .client(client)
                    .balance(balance)
                    .availableBalance(balance)
                    .build();
        }

        @Test
        @DisplayName("top-up koristi pessimistic lock NA KARTICI (findByIdForUpdate), ne findById")
        void topUpUsesCardPessimisticLock() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            Card card = prepaidCard();
            Account src = fundedAccount(1L, personalAccount.getAccountNumber(), new BigDecimal("500.00"));
            when(cardRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(card));
            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(src));
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

            cardService.topUpPrepaidCard(50L, 1L, new BigDecimal("100.00"));

            // Lock mora biti na kartici (serijalizuje paralelne dopune iste kartice
            // i kad dolaze iz razlicitih izvornih racuna). Non-locking findById bi
            // dozvolio lost-update.
            verify(cardRepository).findByIdForUpdate(50L);
            verify(cardRepository, never()).findById(50L);
        }

        @Test
        @DisplayName("withdraw koristi pessimistic lock NA KARTICI (findByIdForUpdate), ne findById")
        void withdrawUsesCardPessimisticLock() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            Card card = prepaidCard();
            card.setPrepaidBalance(new BigDecimal("200.00"));
            Account target = fundedAccount(1L, personalAccount.getAccountNumber(), new BigDecimal("0.00"));
            when(cardRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(card));
            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(target));
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

            cardService.withdrawFromPrepaidCard(50L, 1L, new BigDecimal("50.00"));

            verify(cardRepository).findByIdForUpdate(50L);
            verify(cardRepository, never()).findById(50L);
        }

        @Test
        @DisplayName("P1-authz-idor-1 (R1 115): top-up tudje NE-aktivne NE-prepaid kartice -> 'Nemate pristup' PRE provere stanja")
        void topUpForeignCard_ownershipBeforeState() {
            // Kartica pripada DRUGOM klijentu i NIJE prepaid i NIJE aktivna. Pre fix-a
            // bi pozivalac dobio "nije INTERNET_PREPAID" / "nije aktivna" (curenje tipa
            // i stanja tudje kartice). Posle fix-a: prvo ownership -> "Nemate pristup".
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));

            rs.raf.banka2_bek.client.model.Client other =
                    rs.raf.banka2_bek.client.model.Client.builder().id(987L).email("other@test.com").build();
            Card foreign = Card.builder()
                    .id(50L)
                    .cardNumber("4111110000000000")
                    .account(personalAccount)
                    .client(other)
                    .status(CardStatus.BLOCKED) // ne-aktivna
                    .cardCategory(rs.raf.banka2_bek.card.model.CardCategory.DEBIT) // ne-prepaid
                    .prepaidBalance(BigDecimal.ZERO)
                    .build();
            when(cardRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> cardService.topUpPrepaidCard(50L, 1L, new BigDecimal("100.00")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Nemate pristup");
            // ne sme da dotakne izvorni racun (ownership pukne pre)
            verify(accountRepository, never()).findForUpdateById(1L);
        }

        @Test
        @DisplayName("P1-authz-idor-1 (R1 115): withdraw tudje NE-prepaid kartice -> 'Nemate pristup' PRE provere balansa")
        void withdrawForeignCard_ownershipBeforeState() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));

            rs.raf.banka2_bek.client.model.Client other =
                    rs.raf.banka2_bek.client.model.Client.builder().id(987L).email("other@test.com").build();
            Card foreign = Card.builder()
                    .id(50L)
                    .cardNumber("4111110000000000")
                    .account(personalAccount)
                    .client(other)
                    .status(CardStatus.ACTIVE)
                    .cardCategory(rs.raf.banka2_bek.card.model.CardCategory.DEBIT) // ne-prepaid
                    .prepaidBalance(new BigDecimal("0.00")) // bi inace pukao "Nedovoljno sredstava"
                    .build();
            when(cardRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> cardService.withdrawFromPrepaidCard(50L, 1L, new BigDecimal("50.00")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Nemate pristup");
            verify(accountRepository, never()).findForUpdateById(1L);
        }

        @Test
        @DisplayName("dve dopune iz DVA RAZLICITA racuna obe se primene na karticu (bez lost-update)")
        void twoTopUpsFromDifferentAccountsBothApply() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            // Ista kartica (isti red, zato isti objekat — pessimistic lock garantuje
            // da druga transakcija vidi prvu izmenu pre svog read-a). Dva izvorna
            // racuna su razlicita -> lock na racunu se NE bi serijalizovao; lock na
            // kartici se serijalizuje i oba inkrementa se primene.
            Card card = prepaidCard();
            Account accA = fundedAccount(1L, "222000112345678911", new BigDecimal("500.00"));
            Account accB = fundedAccount(3L, "222000112345678913", new BigDecimal("500.00"));

            when(cardRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(card));
            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(accA));
            when(accountRepository.findForUpdateById(3L)).thenReturn(Optional.of(accB));
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

            cardService.topUpPrepaidCard(50L, 1L, new BigDecimal("100.00"));
            cardService.topUpPrepaidCard(50L, 3L, new BigDecimal("70.00"));

            // Oba iznosa moraju da se vide na kartici: 0 + 100 + 70 = 170 (no lost update).
            assertThat(card.getPrepaidBalance()).isEqualByComparingTo("170.00");
            // Oba izvorna racuna su zaduzena.
            assertThat(accA.getAvailableBalance()).isEqualByComparingTo("400.00");
            assertThat(accB.getAvailableBalance()).isEqualByComparingTo("430.00");
        }

        // ── P1-idempotency-1 (R5-1849): replay-safety za prepaid top-up/withdraw ──

        @Test
        @DisplayName("R5-1849: top-up replay sa istim Idempotency-Key -> JEDAN debit (drugi poziv vraca kesirano stanje)")
        void topUpReplaySameIdempotencyKey_singleDebit() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            Card card = prepaidCard();
            Account src = fundedAccount(1L, personalAccount.getAccountNumber(), new BigDecimal("500.00"));
            when(cardRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(card));
            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(src));
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

            String key = "card-topup-50-req-abc-123";
            // Prvi poziv: kljuc jos NIJE kesiran -> izvrsava transfer + upisuje marker.
            when(internalIdempotencyService.findCached(key)).thenReturn(Optional.empty());
            cardService.topUpPrepaidCard(50L, 1L, new BigDecimal("100.00"), "req-abc-123");

            // Posle prvog poziva marker postoji. Drugi (replay) poziv vidi kesirano ->
            // mora da VRATI kesirano stanje BEZ ponovnog debita.
            InternalRequest cached = new InternalRequest();
            when(internalIdempotencyService.findCached(key)).thenReturn(Optional.of(cached));
            cardService.topUpPrepaidCard(50L, 1L, new BigDecimal("100.00"), "req-abc-123");

            // Kartica je dopunjena TACNO JEDNOM (0 + 100 = 100, NE 200).
            assertThat(card.getPrepaidBalance()).isEqualByComparingTo("100.00");
            // Izvorni racun je zaduzen TACNO JEDNOM (500 - 100 = 400, NE 300).
            assertThat(src.getAvailableBalance()).isEqualByComparingTo("400.00");
            // Marker je upisan tacno jednom (prva, ne-replay putanja).
            verify(internalIdempotencyService, times(1))
                    .store(eq(key), anyString(), eq(200), anyString());
        }

        @Test
        @DisplayName("R5-1849: withdraw replay sa istim Idempotency-Key -> JEDAN prenos")
        void withdrawReplaySameIdempotencyKey_singlePayout() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            Card card = prepaidCard();
            card.setPrepaidBalance(new BigDecimal("300.00"));
            Account target = fundedAccount(1L, personalAccount.getAccountNumber(), new BigDecimal("0.00"));
            when(cardRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(card));
            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(target));
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

            String key = "card-withdraw-50-w-9";
            when(internalIdempotencyService.findCached(key)).thenReturn(Optional.empty());
            cardService.withdrawFromPrepaidCard(50L, 1L, new BigDecimal("120.00"), "w-9");

            when(internalIdempotencyService.findCached(key)).thenReturn(Optional.of(new InternalRequest()));
            cardService.withdrawFromPrepaidCard(50L, 1L, new BigDecimal("120.00"), "w-9");

            // Prenos se desio tacno jednom: kartica 300 - 120 = 180; racun 0 + 120 = 120.
            assertThat(card.getPrepaidBalance()).isEqualByComparingTo("180.00");
            assertThat(target.getAvailableBalance()).isEqualByComparingTo("120.00");
            verify(internalIdempotencyService, times(1))
                    .store(eq(key), anyString(), eq(200), anyString());
        }

        @Test
        @DisplayName("R5-1849: bez Idempotency-Key (stari klijent) -> dedup store se NE dira, ponasanje nepromenjeno")
        void topUpWithoutKey_doesNotTouchDedupStore() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            Card card = prepaidCard();
            Account src = fundedAccount(1L, personalAccount.getAccountNumber(), new BigDecimal("500.00"));
            when(cardRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(card));
            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(src));
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

            // null kljuc (preko stare 3-arg signature) -> nikad ne konsultuje/ne pise dedup.
            cardService.topUpPrepaidCard(50L, 1L, new BigDecimal("100.00"));

            assertThat(card.getPrepaidBalance()).isEqualByComparingTo("100.00");
            verifyNoInteractions(internalIdempotencyService);
        }
    }

    @Nested
    @DisplayName("TEST-cards-2 — prepaid top-up rejection matrix (vlasnik, BLOCKED, insufficient, non-owner-source, negativan)")
    class TopUpRejectionMatrix {

        /** Aktivna INTERNET_PREPAID kartica koja PRIPADA ulogovanom klijentu (#1). */
        private Card ownPrepaidCard() {
            return Card.builder()
                    .id(50L).cardNumber("4222009999999999").cardName("Visa Internet Prepaid")
                    .cvv("321").account(personalAccount).client(client)
                    .status(CardStatus.ACTIVE)
                    .cardCategory(rs.raf.banka2_bek.card.model.CardCategory.INTERNET_PREPAID)
                    .prepaidBalance(new BigDecimal("0.00")).cardLimit(BigDecimal.ZERO)
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();
        }

        private Account fundedAccount(Long id, String number, BigDecimal balance) {
            return Account.builder().id(id).accountNumber(number)
                    .accountType(AccountType.CHECKING).client(client)
                    .balance(balance).availableBalance(balance).build();
        }

        @Test
        @DisplayName("negativan/nula iznos -> IllegalArgumentException PRE bilo kakvog repo poziva")
        void negativeAmount_rejectedBeforeRepoLookup() {
            mockAuth("stefan@test.com");
            // amount<=0 se odbija pre findByIdForUpdate (guard je prvi posle dedup-skip).
            assertThatThrownBy(() -> cardService.topUpPrepaidCard(50L, 1L, new BigDecimal("-5.00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("veci od 0");
            verify(cardRepository, never()).findByIdForUpdate(anyLong());
        }

        @Test
        @DisplayName("nula iznos -> IllegalArgumentException")
        void zeroAmount_rejected() {
            mockAuth("stefan@test.com");
            assertThatThrownBy(() -> cardService.topUpPrepaidCard(50L, 1L, BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("veci od 0");
        }

        @Test
        @DisplayName("VLASNIKOVA ali ne-INTERNET_PREPAID kartica -> IllegalStateException (dopuna samo za prepaid)")
        void ownNonPrepaidCard_rejected() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            Card debit = ownPrepaidCard();
            debit.setCardCategory(rs.raf.banka2_bek.card.model.CardCategory.DEBIT);
            when(cardRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(debit));

            assertThatThrownBy(() -> cardService.topUpPrepaidCard(50L, 1L, new BigDecimal("100.00")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("INTERNET_PREPAID");
            verify(accountRepository, never()).findForUpdateById(anyLong());
        }

        @Test
        @DisplayName("VLASNIKOVA prepaid kartica koja je BLOCKED -> IllegalStateException (nije aktivna)")
        void ownBlockedPrepaidCard_rejected() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            Card blocked = ownPrepaidCard();
            blocked.setStatus(CardStatus.BLOCKED);
            when(cardRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(blocked));

            assertThatThrownBy(() -> cardService.topUpPrepaidCard(50L, 1L, new BigDecimal("100.00")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nije aktivna");
            verify(accountRepository, never()).findForUpdateById(anyLong());
        }

        @Test
        @DisplayName("izvorni racun NE pripada vlasniku kartice -> 'mora pripadati vlasniku kartice'")
        void sourceAccountNotOwnedByCardOwner_rejected() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            Card card = ownPrepaidCard();
            when(cardRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(card));

            // Izvorni racun pripada DRUGOM klijentu (#99) — vlasnik racuna != vlasnik kartice.
            Client other = Client.builder().id(99L).email("other@test.com").build();
            Account foreignSrc = Account.builder().id(7L).accountNumber("222000112345678999")
                    .accountType(AccountType.CHECKING).client(other)
                    .balance(new BigDecimal("9999")).availableBalance(new BigDecimal("9999")).build();
            when(accountRepository.findForUpdateById(7L)).thenReturn(Optional.of(foreignSrc));

            assertThatThrownBy(() -> cardService.topUpPrepaidCard(50L, 7L, new BigDecimal("100.00")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("mora pripadati vlasniku kartice");
            // Novac se ne sme pomeriti.
            assertThat(card.getPrepaidBalance()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("nedovoljno sredstava na izvornom racunu -> IllegalArgumentException, novac se ne pomera")
        void insufficientSourceFunds_rejected() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            Card card = ownPrepaidCard();
            when(cardRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(card));
            // available 50 < zahtev 100
            Account src = fundedAccount(1L, personalAccount.getAccountNumber(), new BigDecimal("50.00"));
            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(src));

            assertThatThrownBy(() -> cardService.topUpPrepaidCard(50L, 1L, new BigDecimal("100.00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Nedovoljno sredstava");
            assertThat(card.getPrepaidBalance()).isEqualByComparingTo("0.00");
            assertThat(src.getAvailableBalance()).isEqualByComparingTo("50.00"); // nedirnuto
        }
    }

    @Nested
    @DisplayName("TEST-cards-3 — prepaid withdraw rejection matrix (insufficient, non-prepaid, target-mismatch, negativan)")
    class WithdrawRejectionMatrix {

        private Card ownPrepaidCard(BigDecimal prepaidBalance) {
            return Card.builder()
                    .id(50L).cardNumber("4222009999999999").cardName("Visa Internet Prepaid")
                    .cvv("321").account(personalAccount).client(client)
                    .status(CardStatus.ACTIVE)
                    .cardCategory(rs.raf.banka2_bek.card.model.CardCategory.INTERNET_PREPAID)
                    .prepaidBalance(prepaidBalance).cardLimit(BigDecimal.ZERO)
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();
        }

        @Test
        @DisplayName("negativan iznos -> IllegalArgumentException PRE repo poziva")
        void negativeAmount_rejectedBeforeRepoLookup() {
            mockAuth("stefan@test.com");
            assertThatThrownBy(() -> cardService.withdrawFromPrepaidCard(50L, 1L, new BigDecimal("-1.00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("veci od 0");
            verify(cardRepository, never()).findByIdForUpdate(anyLong());
        }

        @Test
        @DisplayName("VLASNIKOVA ne-prepaid kartica -> IllegalStateException (povlacenje samo za prepaid)")
        void ownNonPrepaidCard_rejected() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            Card debit = ownPrepaidCard(new BigDecimal("500.00"));
            debit.setCardCategory(rs.raf.banka2_bek.card.model.CardCategory.DEBIT);
            when(cardRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(debit));

            assertThatThrownBy(() -> cardService.withdrawFromPrepaidCard(50L, 1L, new BigDecimal("100.00")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("INTERNET_PREPAID");
            verify(accountRepository, never()).findForUpdateById(anyLong());
        }

        @Test
        @DisplayName("nedovoljno na kartici (prepaidBalance < iznos) -> IllegalArgumentException, novac se ne pomera")
        void insufficientPrepaidBalance_rejected() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            Card card = ownPrepaidCard(new BigDecimal("30.00")); // < 100
            when(cardRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(card));

            assertThatThrownBy(() -> cardService.withdrawFromPrepaidCard(50L, 1L, new BigDecimal("100.00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Nedovoljno sredstava na kartici");
            assertThat(card.getPrepaidBalance()).isEqualByComparingTo("30.00"); // nedirnuto
            // target racun se ne sme ni dohvatiti (balans guard je pre target-lookup-a)
            verify(accountRepository, never()).findForUpdateById(anyLong());
        }

        @Test
        @DisplayName("ciljni racun NE pripada vlasniku kartice -> 'Ciljni racun mora pripadati vlasniku kartice', novac se ne pomera")
        void targetAccountNotOwnedByCardOwner_rejected() {
            mockAuth("stefan@test.com");
            when(clientRepository.findByEmail("stefan@test.com")).thenReturn(Optional.of(client));
            Card card = ownPrepaidCard(new BigDecimal("500.00"));
            when(cardRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(card));

            Client other = Client.builder().id(99L).email("other@test.com").build();
            Account foreignTarget = Account.builder().id(8L).accountNumber("222000112345678998")
                    .accountType(AccountType.CHECKING).client(other)
                    .balance(new BigDecimal("0.00")).availableBalance(new BigDecimal("0.00")).build();
            when(accountRepository.findForUpdateById(8L)).thenReturn(Optional.of(foreignTarget));

            assertThatThrownBy(() -> cardService.withdrawFromPrepaidCard(50L, 8L, new BigDecimal("100.00")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Ciljni racun mora pripadati vlasniku kartice");
            // prepaidBalance je vec umanjen u kodu PRE target-ownership provere? Ne — provera ide
            // posle subtract-a; pinujemo da target racun nije kreditiran (novac nije stigao).
            assertThat(foreignTarget.getAvailableBalance()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("TEST-cards-6 — updateCardLimit na BLOCKED kartici (BE karakterizacija vs FE divergencija)")
    class UpdateLimitOnBlockedCharacterization {

        /**
         * TEST-cards-6: FE moze sakriti dugme za promenu limita na BLOCKED kartici,
         * ali BE eksplicitno DOZVOLJAVA promenu limita dok je kartica BLOCKED
         * (samo DEACTIVATED je zabranjeno — vidi updateCardLimit guard). Ovaj test
         * pinuje BE ponasanje da FE/BE divergencija ne bude slucajno "popravljena"
         * promenom servisa (sto bi bila tiha regresija ugovora).
         */
        @Test
        @DisplayName("BE DOZVOLJAVA promenu limita BLOCKED kartice (samo DEACTIVATED je zabranjeno)")
        void blockedCardLimitChangeAllowed_be() {
            Card blocked = Card.builder()
                    .id(1L).cardNumber("4222001234567890").cardName("Visa Debit")
                    .cvv("123").account(personalAccount).client(client)
                    .status(CardStatus.BLOCKED).cardLimit(BigDecimal.valueOf(100000))
                    .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4)).build();
            when(cardRepository.findById(1L)).thenReturn(Optional.of(blocked));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CardResponseDto result = cardService.updateCardLimit(1L, BigDecimal.valueOf(420000));

            assertThat(result.getCardLimit()).isEqualByComparingTo("420000");
            assertThat(result.getStatus()).isEqualTo(CardStatus.BLOCKED); // status nepromenjen
        }

        @Test
        @DisplayName("BE ZABRANJUJE promenu limita DEACTIVATED kartice (kontrast sa BLOCKED)")
        void deactivatedCardLimitChangeForbidden_be() {
            Card deactivated = Card.builder().id(2L).status(CardStatus.DEACTIVATED).build();
            when(cardRepository.findById(2L)).thenReturn(Optional.of(deactivated));

            assertThatThrownBy(() -> cardService.updateCardLimit(2L, BigDecimal.valueOf(420000)))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("deaktivirane");
        }
    }

    @Nested
    @DisplayName("R1 317 — expireDueCards (istekla kartica izlazi iz ACTIVE)")
    class ExpireDueCards {

        private Card cardWithExpiry(Long id, CardStatus status, LocalDate expiration) {
            return Card.builder()
                    .id(id)
                    .cardNumber("4222001234567890")
                    .cardName("Test Card")
                    .cvv("123")
                    .account(personalAccount)
                    .client(client)
                    .status(status)
                    .createdAt(LocalDate.now().minusYears(5))
                    .expirationDate(expiration)
                    .build();
        }

        @Test
        @DisplayName("istekla ACTIVE kartica -> DEACTIVATED (vise nije ACTIVE)")
        void expiredActiveCard_becomesDeactivated() {
            LocalDate today = LocalDate.of(2026, 6, 1);
            Card expired = cardWithExpiry(1L, CardStatus.ACTIVE, today.minusDays(1));
            when(cardRepository.findExpiredWithStatusNot(today, CardStatus.DEACTIVATED))
                    .thenReturn(List.of(expired));
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

            int count = cardService.expireDueCards(today);

            assertThat(count).isEqualTo(1);
            assertThat(expired.getStatus()).isEqualTo(CardStatus.DEACTIVATED);
            verify(cardRepository).save(expired);
            // Audit traga za sistemsku deaktivaciju.
            verify(auditLogService).record(eq(0L), eq("SYSTEM"),
                    eq(rs.raf.banka2_bek.audit.model.AuditActionType.CARD_DEACTIVATED),
                    anyString(), eq("CARD"), eq(1L));
        }

        @Test
        @DisplayName("istekla BLOCKED kartica -> DEACTIVATED")
        void expiredBlockedCard_becomesDeactivated() {
            LocalDate today = LocalDate.of(2026, 6, 1);
            Card expired = cardWithExpiry(2L, CardStatus.BLOCKED, today.minusDays(10));
            when(cardRepository.findExpiredWithStatusNot(today, CardStatus.DEACTIVATED))
                    .thenReturn(List.of(expired));
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

            int count = cardService.expireDueCards(today);

            assertThat(count).isEqualTo(1);
            assertThat(expired.getStatus()).isEqualTo(CardStatus.DEACTIVATED);
        }

        @Test
        @DisplayName("nema isteklih -> 0, nema save")
        void noExpiredCards_returnsZero() {
            LocalDate today = LocalDate.of(2026, 6, 1);
            when(cardRepository.findExpiredWithStatusNot(today, CardStatus.DEACTIVATED))
                    .thenReturn(List.of());

            int count = cardService.expireDueCards(today);

            assertThat(count).isZero();
            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * TEST-cards-5: expiry logika POSTOJI ({@link CardServiceImpl#expireDueCards})
         * — nije feature-gap. Karakterisemo da kartica cija expirationDate jos NIJE
         * prosla NE ulazi u skup (repo query {@code findExpiredWithStatusNot} filtrira
         * cutoff-om), pa expireDueCards na nju ne dira (ostaje ACTIVE). Tako pinujemo
         * da auto-deaktivacija pogadja SAMO istekle kartice.
         */
        @Test
        @DisplayName("TEST-cards-5: kartica koja JOS nije istekla nije u skupu -> nedirnuta")
        void notYetExpiredCard_notDeactivated() {
            LocalDate today = LocalDate.of(2026, 6, 1);
            // expirationDate u buducnosti -> repo (cutoff filter) je NE vraca.
            when(cardRepository.findExpiredWithStatusNot(today, CardStatus.DEACTIVATED))
                    .thenReturn(List.of());

            int count = cardService.expireDueCards(today);

            assertThat(count).isZero();
            verify(cardRepository, never()).save(any(Card.class));
        }
    }
}
