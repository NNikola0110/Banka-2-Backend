package rs.raf.banka2_bek.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.model.AccountType;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.currency.repository.CurrencyRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.internalapi.model.FundReservation;
import rs.raf.banka2_bek.internalapi.model.FundReservationStatus;
import rs.raf.banka2_bek.internalapi.repository.FundReservationRepository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2-schema-1 PG-DDL smoke za banka-core entitete protiv PRAVOG PostgreSQL-a
 * (Testcontainers), preko punog {@code @SpringBootTest} konteksta (kompletan
 * realan schema graf, {@code ddl-auto=create} kao u test profilu, ali na PG
 * dijalektu — ne H2). H2 (test DB) ne reprodukuje verno PG CHECK constraint-e
 * ni unique-index dedup, pa ova provera ide protiv pravog postgres kontejnera.
 *
 * <p>Pokriva:
 * <ul>
 *   <li><b>R4-1775</b>: {@code accounts} ima CHECK constraint
 *       {@code chk_account_single_owner} =
 *       {@code (client_id IS NULL) <> (company_id IS NULL)} — insert sa OBA ili
 *       BEZ vlasnika (zaobilazenjem @AssertTrue) puca na DB nivou.</li>
 *   <li><b>R4-1774</b>: {@code fund_reservations.reservation_id} ima TACNO JEDAN
 *       unique constraint (ranije dva — {@code @Column(unique)} + redundantan
 *       {@code @Index(unique)}); duplikat reservation_id puca.</li>
 * </ul>
 *
 * <p>{@code @EnabledIf("dockerAvailable")}: ceo test (i container i Spring kontekst)
 * se gracefully preskace bez Docker-a — isti obrazac kao trading PG smoke testovi.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
@SpringBootTest
@ActiveProfiles("test")
class BankaCoreSchemaPostgresDdlTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        // Preusmeravamo @SpringBootTest sa H2 (test profil) na pravi PG kontejner.
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired private AccountRepository accountRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private CurrencyRepository currencyRepository;
    @Autowired private FundReservationRepository fundReservationRepository;
    @Autowired private DataSource dataSource;

    // ── R4-1775: Account CHECK constraint ────────────────────────────────────

    @Test
    void accounts_hasSingleOwnerCheckConstraint_R4_1775() throws Exception {
        assertThat(checkConstraintExists("accounts", "chk_account_single_owner"))
                .as("accounts mora imati CHECK constraint chk_account_single_owner na pravom PG-u")
                .isTrue();
    }

    @Test
    void account_validClientOnly_persists_R4_1775() {
        Currency rsd = ensureCurrency("RSD");
        Client client = persistClient("chk-valid");
        Employee employee = persistEmployee("chk-valid");

        Account account = accountRepository.save(baseAccount("222000000000099001", rsd, employee)
                .client(client).company(null).build());
        assertThat(account.getId()).isNotNull();
    }

    @Test
    void account_noOwner_violatesCheckConstraint_R4_1775() {
        Currency rsd = ensureCurrency("RSD");
        Employee employee = persistEmployee("chk-noowner");

        // @AssertTrue bismo zaobisli pri direktnom repo save-u sa oba null; DB CHECK
        // ipak mora odbiti (klijent==company==null). Bean-validacija na save bi takodje
        // bacila, pa hvatamo bilo koju RuntimeException (constraint/validacija).
        assertThatThrownBy(() -> {
            accountRepository.saveAndFlush(baseAccount("222000000000099002", rsd, employee)
                    .client(null).company(null).build());
        }).isInstanceOf(RuntimeException.class);
    }

    // ── R1-500-CVV: cards.cvv kolona NE postoji (PCI-DSS Req 3.2) ─────────────

    @Test
    void cards_hasNoCvvColumn_R1_500_CVV() throws Exception {
        // Card.cvv je @Transient → Hibernate ga ne mapira; na svezoj semi
        // (ddl-auto=create) kolona se uopste ne kreira. CardCvvColumnMigration
        // dodatno DROP-uje legacy kolonu na postojecim bazama. CVV se nikad
        // ne sme cuvati at-rest posle autorizacije (PCI-DSS Req 3.2).
        assertThat(columnExists("cards", "cvv"))
                .as("cards.cvv kolona NE sme postojati — CVV se ne cuva at-rest (PCI-DSS)")
                .isFalse();
    }

    // ── R4-1774: FundReservation single unique constraint ────────────────────

    @Test
    void fundReservation_reservationId_hasExactlyOneUniqueConstraint_R4_1774() throws Exception {
        long uniqueIndexes = countUniqueIndexesOnColumn("fund_reservations", "reservation_id");
        assertThat(uniqueIndexes)
                .as("reservation_id sme imati TACNO JEDAN unique constraint/index (ne dva redundantna)")
                .isEqualTo(1L);
    }

    @Test
    void fundReservation_duplicateReservationId_isRejected_R4_1774() {
        fundReservationRepository.saveAndFlush(reservation("dup-res-001"));
        assertThatThrownBy(() ->
                fundReservationRepository.saveAndFlush(reservation("dup-res-001")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private FundReservation reservation(String rid) {
        FundReservation r = new FundReservation();
        r.setReservationId(rid);
        r.setAccountId(1L);
        r.setAmount(new BigDecimal("100.0000"));
        r.setCommittedAmount(BigDecimal.ZERO);
        r.setCurrencyCode("RSD");
        r.setStatus(FundReservationStatus.RESERVED);
        return r;
    }

    private Account.AccountBuilder baseAccount(String number, Currency currency, Employee employee) {
        return Account.builder()
                .accountNumber(number)
                .accountType(AccountType.CHECKING)
                .currency(currency)
                .employee(employee)
                .status(AccountStatus.ACTIVE)
                .balance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .reservedAmount(BigDecimal.ZERO)
                .dailyLimit(new BigDecimal("50000.00"))
                .monthlyLimit(new BigDecimal("200000.00"))
                .dailySpending(BigDecimal.ZERO)
                .monthlySpending(BigDecimal.ZERO);
    }

    private Currency ensureCurrency(String code) {
        return currencyRepository.findByCode(code).orElseGet(() -> {
            Currency c = new Currency();
            c.setCode(code);
            c.setName(code);
            c.setSymbol(code);
            c.setCountry("RS");
            c.setDescription("test");
            c.setActive(true);
            return currencyRepository.save(c);
        });
    }

    private Client persistClient(String suffix) {
        Client c = new Client();
        c.setFirstName("Schema");
        c.setLastName("Test");
        c.setDateOfBirth(LocalDate.of(1995, 1, 1));
        c.setGender("M");
        c.setEmail("schema-client-" + suffix + "@test.com");
        c.setPhone("+381600000001");
        c.setAddress("Test");
        c.setPassword("x");
        c.setSaltPassword("salt");
        c.setActive(true);
        return clientRepository.save(c);
    }

    private Employee persistEmployee(String suffix) {
        return employeeRepository.save(Employee.builder()
                .firstName("Schema").lastName("Test")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender("M")
                .email("schema-emp-" + suffix + "@test.com")
                .phone("+381600000000")
                .address("Test")
                .username("schema-" + suffix)
                .password("x")
                .saltPassword("salt")
                .position("QA")
                .department("IT")
                .active(true)
                .permissions(Set.of())
                .build());
    }

    private boolean checkConstraintExists(String table, String constraintName) throws Exception {
        try (Connection c = dataSource.getConnection();
             var ps = c.prepareStatement(
                     "SELECT 1 FROM information_schema.table_constraints "
                             + "WHERE table_name = ? AND constraint_name = ? "
                             + "AND constraint_type = 'CHECK'")) {
            ps.setString(1, table);
            ps.setString(2, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean columnExists(String table, String column) throws Exception {
        try (Connection c = dataSource.getConnection();
             var ps = c.prepareStatement(
                     "SELECT 1 FROM information_schema.columns "
                             + "WHERE table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private long countUniqueIndexesOnColumn(String table, String column) throws Exception {
        // Broji distinktne unique indekse koji pokrivaju (tacno) datu kolonu.
        try (Connection c = dataSource.getConnection();
             var ps = c.prepareStatement(
                     "SELECT COUNT(DISTINCT i.indexrelid) "
                             + "FROM pg_index i "
                             + "JOIN pg_class t ON t.oid = i.indrelid "
                             + "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(i.indkey) "
                             + "WHERE t.relname = ? AND a.attname = ? "
                             + "AND i.indisunique AND i.indnatts = 1")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
