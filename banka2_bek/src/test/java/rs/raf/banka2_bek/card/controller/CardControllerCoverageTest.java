package rs.raf.banka2_bek.card.controller;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.auth.config.GlobalExceptionHandler;
import rs.raf.banka2_bek.card.model.CardRequest;
import rs.raf.banka2_bek.card.model.CardType;
import rs.raf.banka2_bek.card.repository.CardRequestRepository;
import rs.raf.banka2_bek.card.service.CardService;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.card.controller.exception_handler.CardExceptionHandler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("CardController coverage tests")
class CardControllerCoverageTest {

    private MockMvc mockMvc;

    @Mock private CardService cardService;
    @Mock private CardRequestRepository cardRequestRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private ClientRepository clientRepository;

    @InjectMocks private CardController cardController;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SimpleModule pageModule = new SimpleModule();
        pageModule.addSerializer(Page.class, new JsonSerializer<Page>() {
            @Override
            public void serialize(Page value, JsonGenerator gen, SerializerProvider serializers) throws java.io.IOException {
                gen.writeStartObject();
                gen.writeObjectField("content", value.getContent());
                gen.writeNumberField("totalElements", value.getTotalElements());
                gen.writeNumberField("totalPages", value.getTotalPages());
                gen.writeNumberField("number", value.getNumber());
                gen.writeNumberField("size", value.getSize());
                gen.writeEndObject();
            }
        });
        mapper.registerModule(pageModule);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(mapper);

        mockMvc = MockMvcBuilders
                .standaloneSetup(cardController)
                .setMessageConverters(converter)
                .setControllerAdvice(new CardExceptionHandler(), new GlobalExceptionHandler())
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("employee@banka.rs", null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private CardRequest savedRequest(Long id, String status, CardType type) {
        Client owner = new Client();
        owner.setId(5L);
        owner.setFirstName("Marko");
        owner.setLastName("Markovic");
        Account account = Account.builder().id(1L).accountNumber("222000112345678910").client(owner).build();
        return CardRequest.builder()
                .id(id)
                .account(account)
                .cardLimit(new BigDecimal("100000"))
                .cardType(type)
                .clientEmail("marko@banka.rs")
                .clientName("Marko Markovic")
                .status(status)
                .createdAt(LocalDateTime.of(2025, 3, 20, 12, 0))
                .processedAt(LocalDateTime.of(2025, 3, 21, 10, 0))
                .processedBy("employee@banka.rs")
                .build();
    }

    // ---------- POST /cards/requests - cardType branches ----------

    @Test
    @DisplayName("POST /cards/requests - 201 sa validnim cardType MASTERCARD")
    void submitCardRequest_validMastercard() throws Exception {
        Client client = new Client();
        client.setId(5L);
        client.setFirstName("Marko");
        client.setLastName("Markovic");
        when(clientRepository.findByEmail("employee@banka.rs")).thenReturn(Optional.of(client));

        // P1-authz-idor-1 (R1 114): racun mora pripadati ulogovanom klijentu.
        Account account = Account.builder().id(1L).accountNumber("222000112345678910").client(client).build();
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        when(cardRequestRepository.save(any(CardRequest.class))).thenAnswer(inv -> {
            CardRequest r = inv.getArgument(0);
            r.setId(99L);
            r.setCreatedAt(LocalDateTime.now());
            return r;
        });
        // ACCEPTED-DEVIATION (user-directed 03.06): zahtev za karticu bez OTP/koda.
        String payload = """
                {
                  "accountId": 1,
                  "cardLimit": 50000,
                  "cardType": "MASTERCARD"
                }
                """;

        mockMvc.perform(post("/cards/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(99))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(cardRequestRepository).save(argThat(r -> r.getCardType() == CardType.MASTERCARD));
    }

    @Test
    @DisplayName("POST /cards/requests - 201 sa nevalidnim cardType fallback na VISA (catch grana)")
    void submitCardRequest_invalidCardType_fallbackToVisa() throws Exception {
        when(clientRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        Account account = Account.builder().id(1L).accountNumber("222000112345678910").build();
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(cardRequestRepository.save(any(CardRequest.class))).thenAnswer(inv -> {
            CardRequest r = inv.getArgument(0);
            r.setId(100L);
            r.setCreatedAt(LocalDateTime.now());
            return r;
        });

        String payload = """
                {
                  "accountId": 1,
                  "cardType": "INVALID_TYPE"
                }
                """;

        mockMvc.perform(post("/cards/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        verify(cardRequestRepository).save(argThat(r -> r.getCardType() == CardType.VISA));
    }

    // ---------- GET /cards/requests - status filter branches ----------

    @Test
    @DisplayName("GET /cards/requests?status= - prazan string ide na findAll")
    void getAllCardRequests_blankStatus_usesFindAll() throws Exception {
        Page<CardRequest> page = new PageImpl<>(Collections.emptyList());
        when(cardRequestRepository.findAll(any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/cards/requests").param("status", "   "))
                .andExpect(status().isOk());

        verify(cardRequestRepository).findAll(any(Pageable.class));
        verify(cardRequestRepository, never()).findByStatus(anyString(), any(Pageable.class));
    }

    // ---------- PATCH /cards/requests/{id}/approve - cardType null branch ----------

    @Test
    @DisplayName("PATCH /cards/requests/10/approve - koristi cardType iz requesta (non-null grana)")
    void approveCardRequest_withExplicitCardType() throws Exception {
        CardRequest req = savedRequest(10L, "PENDING", CardType.MASTERCARD);
        when(cardRequestRepository.findById(10L)).thenReturn(Optional.of(req));
        when(cardRequestRepository.save(any(CardRequest.class))).thenReturn(req);

        mockMvc.perform(patch("/cards/requests/10/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.cardType").value("MASTERCARD"))
                .andExpect(jsonPath("$.processedAt").exists())
                .andExpect(jsonPath("$.processedBy").value("employee@banka.rs"));

        // Controller sad poziva 6-arg overload (cardCategory + creditLimit dodati 14.05 vece-5)
        verify(cardService).createCardForAccount(eq(1L), eq(5L), any(BigDecimal.class), eq(CardType.MASTERCARD), any(), any());
    }
}
