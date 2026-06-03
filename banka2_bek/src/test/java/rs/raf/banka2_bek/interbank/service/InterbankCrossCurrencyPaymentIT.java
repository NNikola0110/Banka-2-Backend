package rs.raf.banka2_bek.interbank.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import rs.raf.banka2_bek.IntegrationTestCleanup;
import rs.raf.banka2_bek.TestObjectMapperConfig;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.model.AccountType;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.company.model.Company;
import rs.raf.banka2_bek.company.repository.CompanyRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.currency.repository.CurrencyRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.exchange.CurrencyConversionService;
import rs.raf.banka2_bek.interbank.model.InterbankTransaction;
import rs.raf.banka2_bek.interbank.model.InterbankTransactionStatus;
import rs.raf.banka2_bek.interbank.protocol.*;
import rs.raf.banka2_bek.interbank.repository.InterbankTransactionRepository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-3: cross-currency FX + Bank-B provizija u inter-bank 2PC placanjima.
 *
 * <p>Spec Celina 5 §40-66: "Banka B izracunava kurs i proviziju" i kreditira
 * primaocu "Krajnju vrednost" (iznos posle konverzije i provizije) u valuti
 * primaocevog racuna. Wire poruka nosi "Pocetnu vrednost" (originalni iznos +
 * valuta posiljaoca); Banka B (recipient) interno konvertuje pri commit-u.
 *
 * <p>Ovi testovi voze recipient (Banka B) inbound putanju direktno
 * ({@code handleNewTx} → {@code handleCommitTx}) sa lokalnim primaocem cija se
 * valuta razlikuje od wire valute, jer to je tacka gde se FX desava po spec-u.
 */
@Import(TestObjectMapperConfig.class)
@SpringBootTest
@ActiveProfiles("test")
class InterbankCrossCurrencyPaymentIT {

    private static final int SENDER_RN = 999;   // partner Banka A (posiljalac)
    private static final String BANK_REG = "22200022"; // bank.registration-number iz test props

    @MockitoBean
    private InterbankClient interbankClient; // recipient putanja ne salje odlazne poruke

    @Autowired private TransactionExecutorService transactionExecutorService;
    @Autowired private AccountRepository          accountRepository;
    @Autowired private ClientRepository           clientRepository;
    @Autowired private EmployeeRepository         employeeRepository;
    @Autowired private CompanyRepository          companyRepository;
    @Autowired private CurrencyRepository         currencyRepository;
    @Autowired private CurrencyConversionService  currencyConversionService;
    @Autowired private InterbankTransactionRepository txRepo;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource   dataSource;

    /** Inter-bank settlement provizija (0.5%) — mora odgovarati InterbankFxService. */
    private static final BigDecimal SETTLEMENT_FEE = new BigDecimal("0.005");

    @BeforeEach
    void resetDatabase() {
        IntegrationTestCleanup.truncateAllTables(dataSource);
    }

    // =========================================================================
    // Test 1 — Cross-currency: recipient EUR account, sender RSD → krediti
    //          konvertovan iznos minus provizija; banka dobija proviziju.
    // =========================================================================

    @Test
    @DisplayName("Cross-currency: primalac EUR kreditiran (RSD→EUR) − provizija; banka EUR provizija; novac ocuvan")
    void crossCurrency_creditsConvertedAmountMinusCommission() {
        Employee emp = seedEmployee("xc-emp@test.com", "xc-emp");
        Client recipient = seedClient("xc-recipient@test.com");
        Company bank = seedBank();
        Currency rsd = seedCurrency("RSD");
        Currency eur = seedCurrency("EUR");

        // Primalac ima EUR racun kod nase banke (Banka B).
        String recipientNum = "222000000000007777";
        seedAccount(recipientNum, recipient, emp, eur, BigDecimal.ZERO);

        // Bankin EUR commission/pool racun (mirror same-bank cross-currency flow:
        // banka isplacuje target valutu primaocu i prima proviziju).
        String bankEurNum = "222000100000000991";
        BigDecimal bankStart = new BigDecimal("100000.00");
        seedBankAccount(bankEurNum, bank, emp, eur, bankStart);

        // N5 — bankin RSD (source-ccy) pool racun: prima wire 'amount' kao priliv,
        // cime je inter-bank konzervacija na knjigama Banke B 0.
        String bankRsdNum = "222000100000000992";
        BigDecimal bankRsdStart = new BigDecimal("0.00");
        seedBankAccount(bankRsdNum, bank, emp, rsd, bankRsdStart);

        // Sender posting (off-book za nas — remote routing 999), wire valuta = RSD.
        String senderNum = "999000000000001111";
        BigDecimal amountRsd = new BigDecimal("117000.00");

        // Wire tx: balansirano po RSD (posiljalac −amount, primalac +amount).
        // Primalac ima EUR racun → Banka B mora konvertovati RSD→EUR.
        Transaction tx = new Transaction(
                List.of(
                        new Posting(new TxAccount.Account(recipientNum), amountRsd,
                                new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD))),
                        new Posting(new TxAccount.Account(senderNum), amountRsd.negate(),
                                new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD)))
                ),
                new ForeignBankId(SENDER_RN, "xc-tx-0001"),
                "Cross-currency interbank payment", null, "289", "test");

        IdempotenceKey newTxKey = new IdempotenceKey(SENDER_RN, "xc-key-newtx");
        TransactionVote vote = transactionExecutorService.handleNewTx(tx, newTxKey);
        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.YES);

        transactionExecutorService.handleCommitTx(
                new CommitTransaction(tx.transactionId()), new IdempotenceKey(SENDER_RN, "xc-key-commit"));

        // Ocekivana konverzija: mid-rate RSD→EUR, pa primalac = converted − provizija.
        BigDecimal converted = currencyConversionService.convert(amountRsd, "RSD", "EUR");
        BigDecimal fee = converted.multiply(SETTLEMENT_FEE).setScale(4, RoundingMode.HALF_UP);
        BigDecimal recipientCredit = converted.subtract(fee);

        Account recipientAfter = accountRepository.findByAccountNumber(recipientNum).orElseThrow();
        Account bankEurAfter = accountRepository.findByAccountNumber(bankEurNum).orElseThrow();
        Account bankRsdAfter = accountRepository.findByAccountNumber(bankRsdNum).orElseThrow();

        // Primalac dobija konvertovan iznos MINUS provizija (NE sirovi RSD iznos).
        assertThat(recipientAfter.getBalance()).isEqualByComparingTo(recipientCredit);
        assertThat(recipientAfter.getAvailableBalance()).isEqualByComparingTo(recipientCredit);
        assertThat(recipientAfter.getBalance()).isNotEqualByComparingTo(amountRsd);

        // Bankin EUR racun: −converted (isplata primaocu) +fee (provizija) = −recipientCredit.
        BigDecimal bankExpected = bankStart.subtract(recipientCredit);
        assertThat(bankEurAfter.getBalance()).isEqualByComparingTo(bankExpected);

        // N5 KONZERVACIJA: source-ccy (RSD) pool prima TACNO wire 'amount'.
        assertThat(bankRsdAfter.getBalance()).isEqualByComparingTo(bankRsdStart.add(amountRsd));
        assertThat(bankRsdAfter.getAvailableBalance()).isEqualByComparingTo(bankRsdStart.add(amountRsd));

        // Ocuvanje novca na Banci B knjigama: banka isplati 'converted' EUR ukupno
        // (recipientCredit primaocu + fee nazad sebi). Suma kretanja = converted.
        BigDecimal bankDelta = bankStart.subtract(bankEurAfter.getBalance());
        assertThat(recipientAfter.getBalance().add(fee)).isEqualByComparingTo(converted);
        assertThat(bankDelta).isEqualByComparingTo(recipientCredit);

        // Tx status COMMITTED.
        assertThat(txStatus(tx.transactionId())).isEqualTo(InterbankTransactionStatus.COMMITTED);
    }

    // =========================================================================
    // Test 2 — Same-currency: nema FX ni provizije (regression guard).
    // =========================================================================

    @Test
    @DisplayName("Same-currency: primalac kreditiran tacno 'amount', provizija 0, kurs 1 (regression guard)")
    void sameCurrency_unchanged_noFeeNoConversion() {
        Employee emp = seedEmployee("sc-emp@test.com", "sc-emp");
        Client recipient = seedClient("sc-recipient@test.com");
        Currency rsd = seedCurrency("RSD");

        String recipientNum = "222000000000008888";
        seedAccount(recipientNum, recipient, emp, rsd, BigDecimal.ZERO);

        String senderNum = "999000000000002222";
        BigDecimal amount = new BigDecimal("250.00");

        Transaction tx = new Transaction(
                List.of(
                        new Posting(new TxAccount.Account(recipientNum), amount,
                                new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD))),
                        new Posting(new TxAccount.Account(senderNum), amount.negate(),
                                new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD)))
                ),
                new ForeignBankId(SENDER_RN, "sc-tx-0001"),
                "Same-currency interbank payment", null, "289", "test");

        TransactionVote vote = transactionExecutorService.handleNewTx(tx, new IdempotenceKey(SENDER_RN, "sc-key-newtx"));
        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.YES);

        transactionExecutorService.handleCommitTx(
                new CommitTransaction(tx.transactionId()), new IdempotenceKey(SENDER_RN, "sc-key-commit"));

        Account recipientAfter = accountRepository.findByAccountNumber(recipientNum).orElseThrow();
        // Primalac dobija TACNO amount — bez konverzije, bez provizije.
        assertThat(recipientAfter.getBalance()).isEqualByComparingTo(amount);
        assertThat(recipientAfter.getAvailableBalance()).isEqualByComparingTo(amount);

        assertThat(txStatus(tx.transactionId())).isEqualTo(InterbankTransactionStatus.COMMITTED);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private InterbankTransactionStatus txStatus(ForeignBankId id) {
        InterbankTransaction ibTx = txRepo.findByTransactionRoutingNumberAndTransactionIdString(
                id.routingNumber(), id.id()).orElseThrow();
        return ibTx.getStatus();
    }

    private Company seedBank() {
        return companyRepository.save(Company.builder()
                .name("Banka 2025 Tim 2").registrationNumber(BANK_REG)
                .taxNumber("222000222").activityCode("64.19").address("Test")
                .build());
    }

    private Client seedClient(String email) {
        Client c = new Client();
        c.setFirstName("Test");
        c.setLastName("XC");
        c.setDateOfBirth(LocalDate.of(1990, 1, 1));
        c.setGender("M");
        c.setEmail(email);
        c.setPhone("+381600000001");
        c.setAddress("Test Address");
        c.setPassword("x");
        c.setSaltPassword("salt");
        c.setActive(true);
        return clientRepository.save(c);
    }

    private Employee seedEmployee(String email, String username) {
        return employeeRepository.save(Employee.builder()
                .firstName("Emp").lastName("XC")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender("M").email(email).phone("+381600000000")
                .address("Test").username(username)
                .password("x").saltPassword("salt")
                .position("QA").department("IT")
                .active(true).permissions(Set.of())
                .build());
    }

    private Currency seedCurrency(String code) {
        Long id;
        List<Long> ids = jdbcTemplate.query(
                "select id from currencies where code = ?",
                (rs, i) -> rs.getLong(1), code);
        if (ids.isEmpty()) {
            jdbcTemplate.update(
                    "insert into currencies(code, name, symbol, country, description, active) values (?,?,?,?,?,?)",
                    code, code + " currency", code.charAt(0) + "", "RS", "test", true);
            id = jdbcTemplate.queryForObject("select id from currencies where code = ?", Long.class, code);
        } else {
            id = ids.get(0);
        }
        return currencyRepository.findById(id).orElseThrow();
    }

    private Account seedAccount(String number, Client owner, Employee emp,
                                Currency currency, BigDecimal balance) {
        return accountRepository.save(Account.builder()
                .accountNumber(number)
                .accountType(AccountType.CHECKING)
                .currency(currency)
                .client(owner)
                .employee(emp)
                .status(AccountStatus.ACTIVE)
                .balance(balance)
                .availableBalance(balance)
                .reservedAmount(BigDecimal.ZERO)
                .dailyLimit(new BigDecimal("999999999.00"))
                .monthlyLimit(new BigDecimal("999999999.00"))
                .dailySpending(BigDecimal.ZERO)
                .monthlySpending(BigDecimal.ZERO)
                .build());
    }

    private Account seedBankAccount(String number, Company company, Employee emp,
                                    Currency currency, BigDecimal balance) {
        return accountRepository.save(Account.builder()
                .accountNumber(number)
                .accountType(AccountType.BUSINESS)
                .currency(currency)
                .company(company)
                .employee(emp)
                .status(AccountStatus.ACTIVE)
                .balance(balance)
                .availableBalance(balance)
                .reservedAmount(BigDecimal.ZERO)
                .dailyLimit(new BigDecimal("999999999.00"))
                .monthlyLimit(new BigDecimal("999999999.00"))
                .dailySpending(BigDecimal.ZERO)
                .monthlySpending(BigDecimal.ZERO)
                .build());
    }
}
