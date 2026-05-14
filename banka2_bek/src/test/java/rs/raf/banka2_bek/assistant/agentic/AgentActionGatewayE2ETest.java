package rs.raf.banka2_bek.assistant.agentic;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import rs.raf.banka2_bek.IntegrationTestCleanup;
import rs.raf.banka2_bek.TestObjectMapperConfig;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.model.AccountType;
import rs.raf.banka2_bek.assistant.agentic.dto.AgentActionPreviewDto;
import rs.raf.banka2_bek.assistant.agentic.dto.ConfirmActionDto;
import rs.raf.banka2_bek.assistant.agentic.model.AgentActionStatus;
import rs.raf.banka2_bek.assistant.agentic.repository.AgentActionRepository;
import rs.raf.banka2_bek.assistant.agentic.service.AgentActionGateway;
import rs.raf.banka2_bek.assistant.tool.WriteToolHandler;
import rs.raf.banka2_bek.assistant.tool.handlers.agentic.CreatePaymentActionHandler;
import rs.raf.banka2_bek.auth.model.User;
import rs.raf.banka2_bek.auth.repository.UserRepository;
import rs.raf.banka2_bek.auth.util.UserContext;
import rs.raf.banka2_bek.auth.util.UserRole;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.exchange.ExchangeService;
import rs.raf.banka2_bek.exchange.dto.ExchangeRateDto;
import rs.raf.banka2_bek.payment.model.Payment;
import rs.raf.banka2_bek.payment.model.PaymentStatus;
import rs.raf.banka2_bek.payment.repository.PaymentAccountRepository;
import rs.raf.banka2_bek.payment.repository.PaymentRepository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Plan v3.6 §Task 4 — E2E test za create_payment agentic flow.
 *
 * <p>Test pokriva ono sto je BUG B2 ranije sprecavalo: kompletan
 * AgentActionGateway pipeline (createPending → confirm → payment row).
 * BE handler stack je: CreatePaymentActionHandler.buildPreview →
 * gateway.createPending → gateway.confirm → handler.executeFinal →
 * PaymentService.createPayment → Payment row sa COMPLETED statusom.</p>
 *
 * <p>Skip-uje LLM chat layer (Task 1-3 vec testirani izolovano) i
 * fokusira na cisto Settlement layer-u (Layer 4 framework-a) da otkrije
 * one bugove koji pukotinom samo u prod-u (B2 hipoteze d i e).</p>
 *
 * <p>Mock-uje samo OtpService.verify i ExchangeService.getAllRates.
 * Sve ostalo (PaymentService, AccountService, JPA, transactions) je
 * REAL — H2 in-memory baza sa Spring Boot kontekstom.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestObjectMapperConfig.class)
class AgentActionGatewayE2ETest {

    @Autowired private AgentActionGateway gateway;
    @Autowired private CreatePaymentActionHandler createPaymentHandler;
    @Autowired private AgentActionRepository agentActionRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentAccountRepository accountRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private EntityManager entityManager;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private rs.raf.banka2_bek.otp.service.OtpService otpService;
    @MockitoBean private ExchangeService exchangeService;

    private static final String SENDER_EMAIL = "stefan.e2e@test.com";
    private static final String SENDER_ACCOUNT = "222000000000000010";
    private static final String RECIPIENT_ACCOUNT = "222000000000000011";

    @BeforeEach
    void setUp() {
        IntegrationTestCleanup.truncateAllTables(dataSource);
        when(otpService.verify(anyString(), anyString()))
                .thenReturn(Map.of("verified", true));
        when(exchangeService.getAllRates()).thenReturn(List.of(
                new ExchangeRateDto("RSD", 1.0)
        ));
        // SecurityContext setuje se posle kreiranja klijenta + user-a u testu.
    }

    @Test
    void createPaymentAgenticFlow_endToEnd_persistsPaymentRow() {
        // 1) Setup: pravi klijenta, employee-ja, valutu, 2 racuna.
        Client sender = createClient(SENDER_EMAIL);
        Client receiver = createClient("milica.e2e@test.com");
        Employee employee = createEmployee("emp.e2e@test.com", "emp_e2e");
        Currency rsd = ensureCurrency("RSD", "Serbian Dinar", "RSD", "RS");

        createAccount(SENDER_ACCOUNT, sender, employee, rsd, new BigDecimal("1000.00"));
        createAccount(RECIPIENT_ACCOUNT, receiver, employee, rsd, new BigDecimal("500.00"));

        // PaymentService.getAuthenticatedUsername() trazi UserDetails iz
        // SecurityContext-a — moramo da kreiramo pravi User entitet sa istim
        // email-om kao klijenta i postavimo ga kao principal.
        User authUser = createAuthUser(sender);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(authUser, "n/a", List.of()));

        UserContext senderCtx = new UserContext(sender.getId(), UserRole.CLIENT);

        // 2) Imitate LLM-emit-ovani args — bez stvarnog LLM poziva.
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("fromAccount", SENDER_ACCOUNT);
        args.put("toAccount", RECIPIENT_ACCOUNT);
        args.put("amount", new BigDecimal("100.00"));
        args.put("description", "E2E test placanje");
        args.put("paymentCode", "CODE_289");

        // 3) Layer 3 (Authorization): createPending → AgentAction sa PENDING.
        AgentActionPreviewDto preview = gateway.createPending(
                "conv-uuid-e2e", "create_payment", args, senderCtx, createPaymentHandler);

        assertThat(preview).isNotNull();
        assertThat(preview.getActionUuid()).isNotBlank();
        assertThat(preview.getTool()).isEqualTo("create_payment");
        assertThat(preview.isRequiresOtp()).isTrue();
        assertThat(preview.getSummary()).contains("Placanje").contains("100").contains("RSD");

        // Verify AgentAction row persisted sa PENDING statusom
        var actionInDb = agentActionRepository.findByActionUuid(preview.getActionUuid());
        assertThat(actionInDb).isPresent();
        assertThat(actionInDb.get().getStatus()).isEqualTo(AgentActionStatus.PENDING);

        // 4) Layer 4 (Settlement): confirm → execute → assert Payment row.
        ConfirmActionDto confirmDto = new ConfirmActionDto();
        confirmDto.setOtpCode("123456");

        Map<String, WriteToolHandler> handlerMap = new HashMap<>();
        handlerMap.put("create_payment", createPaymentHandler);

        Map<String, Object> result = gateway.confirm(
                preview.getActionUuid(), senderCtx, confirmDto, handlerMap);

        // 5) Assertions: gateway response status + Payment row
        assertThat(result).containsEntry("status", "EXECUTED");
        assertThat(result.get("result")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> innerResult = (Map<String, Object>) result.get("result");
        assertThat(innerResult).containsKey("paymentId");
        assertThat(innerResult.get("status")).isEqualTo("COMPLETED");

        // Verify Payment row REALLY exists in DB (B2 acceptance criteria)
        assertThat(paymentRepository.count()).isEqualTo(1L);
        Payment payment = paymentRepository.findAll().iterator().next();
        assertThat(payment.getAmount()).isEqualByComparingTo("100.00");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);

        // Verify AgentAction prelazi u EXECUTED
        var actionAfter = agentActionRepository.findByActionUuid(preview.getActionUuid());
        assertThat(actionAfter).isPresent();
        assertThat(actionAfter.get().getStatus()).isEqualTo(AgentActionStatus.EXECUTED);

        // Verify balansi: sender 1000 - 100 = 900; receiver 500 + 100 = 600
        Account fromAfter = accountRepository.findByAccountNumber(SENDER_ACCOUNT).orElseThrow();
        Account toAfter = accountRepository.findByAccountNumber(RECIPIENT_ACCOUNT).orElseThrow();
        assertThat(fromAfter.getBalance()).isEqualByComparingTo("900.00000");
        assertThat(toAfter.getBalance()).isEqualByComparingTo("600.00000");
    }

    /* ============================== Helpers ============================== */

    private User createAuthUser(Client client) {
        User user = new User();
        user.setFirstName(client.getFirstName());
        user.setLastName(client.getLastName());
        user.setEmail(client.getEmail());
        user.setPassword(client.getPassword());
        user.setActive(Boolean.TRUE.equals(client.getActive()));
        user.setRole("CLIENT");
        return userRepository.save(user);
    }

    private Client createClient(String email) {
        Client user = new Client();
        user.setFirstName("Test");
        user.setLastName("User");
        user.setDateOfBirth(LocalDate.of(1995, 1, 1));
        user.setGender("M");
        user.setEmail(email);
        user.setPhone("+381600000001");
        user.setAddress("Test Address");
        user.setPassword("x");
        user.setSaltPassword("salt");
        user.setActive(true);
        return clientRepository.save(user);
    }

    private Employee createEmployee(String email, String username) {
        Employee employee = Employee.builder()
                .firstName("Emp")
                .lastName("Test")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender("M")
                .email(email)
                .phone("+381600000000")
                .address("Test")
                .username(username)
                .password("x")
                .saltPassword("salt")
                .position("QA")
                .department("IT")
                .active(true)
                .permissions(Set.of("VIEW_STOCKS"))
                .build();
        return employeeRepository.save(employee);
    }

    private Currency ensureCurrency(String code, String name, String symbol, String country) {
        List<Long> ids = jdbcTemplate.query(
                "select id from currencies where code = ?",
                (rs, rowNum) -> rs.getLong(1),
                code);
        Long id;
        if (ids.isEmpty()) {
            jdbcTemplate.update(
                    "insert into currencies(code, name, symbol, country, description, active) values (?, ?, ?, ?, ?, ?)",
                    code, name, symbol, country, "test", true);
            id = jdbcTemplate.queryForObject(
                    "select id from currencies where code = ?", Long.class, code);
        } else {
            id = ids.get(0);
        }
        return entityManager.getReference(Currency.class, id);
    }

    private Account createAccount(String accountNumber, Client owner, Employee employee,
                                   Currency currency, BigDecimal balance) {
        Account account = Account.builder()
                .accountNumber(accountNumber)
                .accountType(AccountType.CHECKING)
                .currency(currency)
                .client(owner)
                .employee(employee)
                .status(AccountStatus.ACTIVE)
                .balance(balance)
                .availableBalance(balance)
                .dailyLimit(new BigDecimal("5000.00"))
                .monthlyLimit(new BigDecimal("20000.00"))
                .dailySpending(BigDecimal.ZERO)
                .monthlySpending(BigDecimal.ZERO)
                .build();
        return accountRepository.save(account);
    }
}
