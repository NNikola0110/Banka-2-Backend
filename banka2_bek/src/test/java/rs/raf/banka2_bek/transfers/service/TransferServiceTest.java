package rs.raf.banka2_bek.transfers.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.exchange.ExchangeService;
import rs.raf.banka2_bek.payment.model.PaymentStatus;
import rs.raf.banka2_bek.transfers.model.Transfer;
import rs.raf.banka2_bek.transfers.dto.TransferFxRequestDto;
import rs.raf.banka2_bek.transfers.dto.TransferInternalRequestDto;
import rs.raf.banka2_bek.transfers.dto.TransferResponseDto;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.transfers.repository.TransferRepository;
import rs.raf.banka2_bek.notification.service.NotificationService;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
public class TransferServiceTest {

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private ExchangeService exchangeService;

    @Mock
    private ClientRepository clientRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private rs.raf.banka2_bek.audit.service.AuditLogService auditLogService;

    private TransferService transferService;

    private Account fromAccount;
    private Account toAccount;
    private Client client;
    private Currency currency;
    private Currency eurCurrency;

    private void authenticateAs(String email) {
        org.springframework.security.core.userdetails.User principal =
                new org.springframework.security.core.userdetails.User(
                        email, "x",
                        List.of(new SimpleGrantedAuthority("ROLE_CLIENT"))
                );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_GLOBAL);
        transferService = new TransferService(
                transferRepository, accountRepository, exchangeService,
                clientRepository, notificationService, auditLogService);
        java.lang.reflect.Field field = TransferService.class.getDeclaredField("bankRegistrationNumber");
        field.setAccessible(true);
        field.set(transferService, "22200022");

        currency = new Currency();
        currency.setId(1L);
        currency.setCode("RSD");

        client = new Client();
        client.setId(1L);
        client.setFirstName("Milica");
        client.setLastName("Zoranovic");
        client.setEmail("milica@test.com");

        fromAccount = new Account();
        fromAccount.setAccountNumber("111111111111111111");
        fromAccount.setClient(client);
        fromAccount.setCurrency(currency);
        fromAccount.setAvailableBalance(new BigDecimal("10000"));
        fromAccount.setBalance(new BigDecimal("10000"));
        fromAccount.setStatus(AccountStatus.ACTIVE);

        toAccount = new Account();
        toAccount.setAccountNumber("222222222222222222");
        toAccount.setClient(client);
        toAccount.setCurrency(currency);
        toAccount.setAvailableBalance(new BigDecimal("5000"));
        toAccount.setBalance(new BigDecimal("5000"));
        toAccount.setStatus(AccountStatus.ACTIVE);

        eurCurrency = new Currency();
        eurCurrency.setId(2L);
        eurCurrency.setCode("EUR");

        authenticateAs("milica@test.com");
        lenient().when(clientRepository.findByEmail("milica@test.com")).thenReturn(Optional.of(client));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        // setUp() prebacuje globalnu SecurityContextHolder strategiju na
        // MODE_GLOBAL — staticko stanje JVM-a. Mora se vratiti na default
        // (MODE_THREADLOCAL) posle svakog testa, inace MODE_GLOBAL "curi" u
        // ostale test klase u istom surefire fork-u i kvari njihovu
        // per-thread SecurityContext izolaciju.
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_THREADLOCAL);
    }

    @Test
    void internalTransferSucceeds() {
        when(accountRepository.findForUpdateByAccountNumber("111111111111111111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findForUpdateByAccountNumber("222222222222222222")).thenReturn(Optional.of(toAccount));
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransferInternalRequestDto request = new TransferInternalRequestDto();
        request.setFromAccountNumber("111111111111111111");
        request.setToAccountNumber("222222222222222222");
        request.setAmount(new BigDecimal("1000"));

        TransferResponseDto response = transferService.internalTransfer(request);

        assertThat(response.getFromAccountNumber()).isEqualTo("111111111111111111");
        assertThat(response.getToAccountNumber()).isEqualTo("222222222222222222");
        assertThat(response.getCommission()).isEqualTo(BigDecimal.ZERO);
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(fromAccount.getAvailableBalance()).isEqualTo(new BigDecimal("9000"));
        assertThat(toAccount.getAvailableBalance()).isEqualTo(new BigDecimal("6000"));
    }

    @Test
    void internalTransfer_locksAccountsInCanonicalOrder_regardlessOfDirection() {
        // P2-concurrency-locks-1 (R3-1581): transfer iz VISEG racuna (222) u NIZI (111)
        // mora i dalje da zakljuca NIZI (111) PRVI (kanonski redosled po account-number-u).
        // Bez ovoga, paralelni 111→222 i 222→111 bi lock-ovali u suprotnim redosledima =
        // ABBA deadlock. InOrder potvrdjuje globalno-konzistentan lock redosled.
        when(accountRepository.findForUpdateByAccountNumber("111111111111111111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findForUpdateByAccountNumber("222222222222222222")).thenReturn(Optional.of(toAccount));
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransferInternalRequestDto request = new TransferInternalRequestDto();
        request.setFromAccountNumber("222222222222222222"); // VISI broj je posiljalac
        request.setToAccountNumber("111111111111111111");   // NIZI broj je primalac
        request.setAmount(new BigDecimal("1000"));

        transferService.internalTransfer(request);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(accountRepository);
        // NIZI account-number (111) se zakljucava PRVI iako je primalac (to).
        inOrder.verify(accountRepository).findForUpdateByAccountNumber("111111111111111111");
        inOrder.verify(accountRepository).findForUpdateByAccountNumber("222222222222222222");
    }

    @Test
    void internalTransferFailsWhenInsufficientFunds() {
        when(accountRepository.findForUpdateByAccountNumber("111111111111111111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findForUpdateByAccountNumber("222222222222222222")).thenReturn(Optional.of(toAccount));

        TransferInternalRequestDto request = new TransferInternalRequestDto();
        request.setFromAccountNumber("111111111111111111");
        request.setToAccountNumber("222222222222222222");
        request.setAmount(new BigDecimal("99999"));

        assertThatThrownBy(() -> transferService.internalTransfer(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Insufficient funds");
    }


    @Test
    void internalTransferFailsWhenDifferentCurrency() {
        Currency eurCurrencyLocal = new Currency();
        eurCurrencyLocal.setId(2L);
        eurCurrencyLocal.setCode("EUR");
        toAccount.setCurrency(eurCurrencyLocal);

        when(accountRepository.findForUpdateByAccountNumber("111111111111111111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findForUpdateByAccountNumber("222222222222222222")).thenReturn(Optional.of(toAccount));

        TransferInternalRequestDto request = new TransferInternalRequestDto();
        request.setFromAccountNumber("111111111111111111");
        request.setToAccountNumber("222222222222222222");
        request.setAmount(new BigDecimal("1000"));

        assertThatThrownBy(() -> transferService.internalTransfer(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Bank account for RSD not found");
    }

    @Test
    void internalTransferFailsWhenAccountNotActive() {
        fromAccount.setStatus(AccountStatus.INACTIVE);

        when(accountRepository.findForUpdateByAccountNumber("111111111111111111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findForUpdateByAccountNumber("222222222222222222")).thenReturn(Optional.of(toAccount));

        TransferInternalRequestDto request = new TransferInternalRequestDto();
        request.setFromAccountNumber("111111111111111111");
        request.setToAccountNumber("222222222222222222");
        request.setAmount(new BigDecimal("1000"));

        assertThatThrownBy(() -> transferService.internalTransfer(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Source account is not active");
    }

    @Test
    void internalTransferFailsWhenDifferentClients() {
        Client otherClient = new Client();
        otherClient.setId(2L);
        otherClient.setFirstName("Luka");
        otherClient.setLastName("Draskovic");
        toAccount.setClient(otherClient);

        when(accountRepository.findForUpdateByAccountNumber("111111111111111111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findForUpdateByAccountNumber("222222222222222222")).thenReturn(Optional.of(toAccount));

        TransferInternalRequestDto request = new TransferInternalRequestDto();
        request.setFromAccountNumber("111111111111111111");
        request.setToAccountNumber("222222222222222222");
        request.setAmount(new BigDecimal("1000"));

        assertThatThrownBy(() -> transferService.internalTransfer(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("do not have access");
    }

    @Test
    void internalTransferFailsWhenCompanyToPersonalDifferentOwner() {
        // P1-authz-idor-1 (R2 1371): ovlasceno lice firme NE sme "internim" transferom
        // (bez provizije/limita) da prebaci firmin novac na svoj LICNI racun. fromAccount
        // je firmin (actor je ovlascen → ensureAccess prolazi), toAccount je actorov licni
        // (ensureAccess prolazi) — ali vlasnici se razlikuju → ensureSameOwner mora odbiti.
        rs.raf.banka2_bek.company.model.Company company =
                new rs.raf.banka2_bek.company.model.Company();
        company.setId(500L);
        rs.raf.banka2_bek.company.model.AuthorizedPerson ap =
                new rs.raf.banka2_bek.company.model.AuthorizedPerson();
        ap.setClient(client);
        ap.setCompany(company);
        company.setAuthorizedPersons(List.of(ap));

        // fromAccount postaje firmin (bez client vlasnika, sa company)
        fromAccount.setClient(null);
        fromAccount.setCompany(company);
        // toAccount ostaje actorov licni racun (client #1)

        when(accountRepository.findForUpdateByAccountNumber("111111111111111111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findForUpdateByAccountNumber("222222222222222222")).thenReturn(Optional.of(toAccount));

        TransferInternalRequestDto request = new TransferInternalRequestDto();
        request.setFromAccountNumber("111111111111111111");
        request.setToAccountNumber("222222222222222222");
        request.setAmount(new BigDecimal("1000"));

        assertThatThrownBy(() -> transferService.internalTransfer(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("istog vlasnika");
    }

    @Test
    void internalTransferFailsWhenSameAccount() {
        // P2-concurrency-locks-1 (R3-1581): same-account zahtev se odbija PRE bilo kakvog
        // lock-a (u lockTwoAccountsCanonically) — zato vise nema findForUpdate stub-a.
        TransferInternalRequestDto request = new TransferInternalRequestDto();
        request.setFromAccountNumber("111111111111111111");
        request.setToAccountNumber("111111111111111111");
        request.setAmount(new BigDecimal("1000"));

        assertThatThrownBy(() -> transferService.internalTransfer(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Accounts must be different");
    }

    @Test
    void getTransferByIdSucceeds() {
        Transfer transfer = new Transfer();
        transfer.setFromAccount(fromAccount);
        transfer.setToAccount(toAccount);
        transfer.setFromAmount(new BigDecimal("1000"));
        transfer.setFromCurrency(currency);
        transfer.setToCurrency(currency);
        transfer.setExchangeRate(null);
        transfer.setCommission(BigDecimal.ZERO);
        transfer.setStatus(PaymentStatus.COMPLETED);
        transfer.setCreatedBy(client);

        when(transferRepository.findById(1L)).thenReturn(Optional.of(transfer));

        TransferResponseDto response = transferService.getTransferById(1L);

        assertThat(response.getFromAccountNumber()).isEqualTo("111111111111111111");
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void getTransferByIdFailsWhenNotFound() {
        when(transferRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.getTransferById(99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Transfer not found");
    }

    @Test
    void fxTransferSucceeds() {
        toAccount.setCurrency(eurCurrency);

        Account bankRsdAccount = new Account();
        bankRsdAccount.setAccountNumber("BANK-RSD");
        bankRsdAccount.setCurrency(currency);
        bankRsdAccount.setBalance(new BigDecimal("1000000"));
        bankRsdAccount.setAvailableBalance(new BigDecimal("1000000"));
        bankRsdAccount.setStatus(AccountStatus.ACTIVE);

        Account bankEurAccount = new Account();
        bankEurAccount.setAccountNumber("BANK-EUR");
        bankEurAccount.setCurrency(eurCurrency);
        bankEurAccount.setBalance(new BigDecimal("1000000"));
        bankEurAccount.setAvailableBalance(new BigDecimal("1000000"));
        bankEurAccount.setStatus(AccountStatus.ACTIVE);

        when(accountRepository.findForUpdateByAccountNumber("111111111111111111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findForUpdateByAccountNumber("222222222222222222")).thenReturn(Optional.of(toAccount));
        when(accountRepository.findBankAccountForUpdateByCurrency("22200022", "RSD")).thenReturn(Optional.of(bankRsdAccount));
        when(accountRepository.findBankAccountForUpdateByCurrency("22200022", "EUR")).thenReturn(Optional.of(bankEurAccount));
        when(exchangeService.calculateCrossExact(new BigDecimal("1000"), "RSD", "EUR"))
                .thenReturn(new ExchangeService.FxConversionResult(new BigDecimal("9.50"), new BigDecimal("0.0095")));
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransferFxRequestDto request = new TransferFxRequestDto();
        request.setFromAccountNumber("111111111111111111");
        request.setToAccountNumber("222222222222222222");
        request.setAmount(new BigDecimal("1000"));

        TransferResponseDto response = transferService.fxTransfer(request);

        assertThat(response.getFromAccountNumber()).isEqualTo("111111111111111111");
        assertThat(response.getToAccountNumber()).isEqualTo("222222222222222222");
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(fromAccount.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("8995.00"));
        assertThat(toAccount.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("5009.5"));
    }

    @Test
    void fxTransferFailsWhenSameCurrency() {
        when(accountRepository.findForUpdateByAccountNumber("111111111111111111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findForUpdateByAccountNumber("222222222222222222")).thenReturn(Optional.of(toAccount));

        TransferFxRequestDto request = new TransferFxRequestDto();
        request.setFromAccountNumber("111111111111111111");
        request.setToAccountNumber("222222222222222222");
        request.setAmount(new BigDecimal("1000"));

        assertThatThrownBy(() -> transferService.fxTransfer(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Accounts must have different currencies");
    }

    @Test
    void fxTransferFailsWhenInsufficientFunds() {
        toAccount.setCurrency(eurCurrency);

        when(accountRepository.findForUpdateByAccountNumber("111111111111111111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findForUpdateByAccountNumber("222222222222222222")).thenReturn(Optional.of(toAccount));

        TransferFxRequestDto request = new TransferFxRequestDto();
        request.setFromAccountNumber("111111111111111111");
        request.setToAccountNumber("222222222222222222");
        request.setAmount(new BigDecimal("99999"));

        assertThatThrownBy(() -> transferService.fxTransfer(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Nedovoljno sredstava");
    }

    @Test
    void fxTransferFailsWhenAffordsAmountButNotCommission_TEST_transfers_2() {
        // TEST-transfers-2 (TransferService:242): klijent ima TACNO za iznos, ali ne
        // i za iznos + provizija 0.5% → odbijeno. fromAccount.available=10000,
        // amount=10000, provizija=50 → totalDebit=10050 > 10000 → reject.
        toAccount.setCurrency(eurCurrency);

        when(accountRepository.findForUpdateByAccountNumber("111111111111111111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findForUpdateByAccountNumber("222222222222222222")).thenReturn(Optional.of(toAccount));

        TransferFxRequestDto request = new TransferFxRequestDto();
        request.setFromAccountNumber("111111111111111111");
        request.setToAccountNumber("222222222222222222");
        request.setAmount(new BigDecimal("10000")); // ima za iznos, ali ne i za iznos+provizija

        assertThatThrownBy(() -> transferService.fxTransfer(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Nedovoljno sredstava")
                .hasMessageContaining("provizija");
    }

    @Test
    void fxTransferFailsWhenBankLacksTargetReserves_TEST_transfers_3() {
        // TEST-transfers-3 / TEST-exchange-3 (TransferService:268): klijent moze da
        // plati iznos+proviziju, ali banka NEMA dovoljno ciljne valute u rezervi →
        // odbijeno (knjige se ne smeju razbalansirati delimicnim transferom).
        toAccount.setCurrency(eurCurrency);

        Account bankRsdAccount = new Account();
        bankRsdAccount.setAccountNumber("BANK-RSD");
        bankRsdAccount.setCurrency(currency);
        bankRsdAccount.setBalance(new BigDecimal("1000000"));
        bankRsdAccount.setAvailableBalance(new BigDecimal("1000000"));
        bankRsdAccount.setStatus(AccountStatus.ACTIVE);

        // Banka EUR racun ima samo 1 EUR — manje od konvertovanog iznosa (9.50 EUR).
        Account bankEurAccount = new Account();
        bankEurAccount.setAccountNumber("BANK-EUR");
        bankEurAccount.setCurrency(eurCurrency);
        bankEurAccount.setBalance(new BigDecimal("1.00"));
        bankEurAccount.setAvailableBalance(new BigDecimal("1.00"));
        bankEurAccount.setStatus(AccountStatus.ACTIVE);

        when(accountRepository.findForUpdateByAccountNumber("111111111111111111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findForUpdateByAccountNumber("222222222222222222")).thenReturn(Optional.of(toAccount));
        when(accountRepository.findBankAccountForUpdateByCurrency("22200022", "RSD")).thenReturn(Optional.of(bankRsdAccount));
        when(accountRepository.findBankAccountForUpdateByCurrency("22200022", "EUR")).thenReturn(Optional.of(bankEurAccount));
        when(exchangeService.calculateCrossExact(new BigDecimal("1000"), "RSD", "EUR"))
                .thenReturn(new ExchangeService.FxConversionResult(new BigDecimal("9.50"), new BigDecimal("0.0095")));

        TransferFxRequestDto request = new TransferFxRequestDto();
        request.setFromAccountNumber("111111111111111111");
        request.setToAccountNumber("222222222222222222");
        request.setAmount(new BigDecimal("1000"));

        assertThatThrownBy(() -> transferService.fxTransfer(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Bank does not have enough EUR");
    }

    @Test
    void fxTransferFailsWhenBankTargetAccountMissing_TEST_exchange_3() {
        // TEST-exchange-3: ako banka uopste NEMA racun u ciljnoj valuti →
        // EntityNotFoundException (mapira se na 500/odbijeno), ne delimican transfer.
        toAccount.setCurrency(eurCurrency);

        Account bankRsdAccount = new Account();
        bankRsdAccount.setAccountNumber("BANK-RSD");
        bankRsdAccount.setCurrency(currency);
        bankRsdAccount.setBalance(new BigDecimal("1000000"));
        bankRsdAccount.setAvailableBalance(new BigDecimal("1000000"));
        bankRsdAccount.setStatus(AccountStatus.ACTIVE);

        when(accountRepository.findForUpdateByAccountNumber("111111111111111111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findForUpdateByAccountNumber("222222222222222222")).thenReturn(Optional.of(toAccount));
        when(accountRepository.findBankAccountForUpdateByCurrency("22200022", "RSD")).thenReturn(Optional.of(bankRsdAccount));
        when(accountRepository.findBankAccountForUpdateByCurrency("22200022", "EUR")).thenReturn(Optional.empty());

        TransferFxRequestDto request = new TransferFxRequestDto();
        request.setFromAccountNumber("111111111111111111");
        request.setToAccountNumber("222222222222222222");
        request.setAmount(new BigDecimal("1000"));

        assertThatThrownBy(() -> transferService.fxTransfer(request))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class)
                .hasMessageContaining("Bank account for EUR not found");
    }

    @Test
    void fxTransferFailsWhenAccountNotActive() {
        fromAccount.setStatus(AccountStatus.INACTIVE);
        toAccount.setCurrency(eurCurrency);

        when(accountRepository.findForUpdateByAccountNumber("111111111111111111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findForUpdateByAccountNumber("222222222222222222")).thenReturn(Optional.of(toAccount));

        TransferFxRequestDto request = new TransferFxRequestDto();
        request.setFromAccountNumber("111111111111111111");
        request.setToAccountNumber("222222222222222222");
        request.setAmount(new BigDecimal("1000"));

        assertThatThrownBy(() -> transferService.fxTransfer(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Source account is not active");
    }

    @Test
    void fxTransferFailsWhenSameAccount() {
        // P2-concurrency-locks-1 (R3-1581): same-account zahtev se odbija PRE lock-a.
        TransferFxRequestDto request = new TransferFxRequestDto();
        request.setFromAccountNumber("111111111111111111");
        request.setToAccountNumber("111111111111111111");
        request.setAmount(new BigDecimal("1000"));

        assertThatThrownBy(() -> transferService.fxTransfer(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Accounts must be different");
    }

    @Test
    void getAllTransfersSucceeds() {
        Transfer transfer1 = new Transfer();
        transfer1.setFromAccount(fromAccount);
        transfer1.setToAccount(toAccount);
        transfer1.setFromAmount(new BigDecimal("1000"));
        transfer1.setFromCurrency(currency);
        transfer1.setToCurrency(currency);
        transfer1.setExchangeRate(null);
        transfer1.setCommission(BigDecimal.ZERO);
        transfer1.setStatus(PaymentStatus.COMPLETED);
        transfer1.setCreatedBy(client);

        Transfer transfer2 = new Transfer();
        transfer2.setFromAccount(fromAccount);
        transfer2.setToAccount(toAccount);
        transfer2.setFromAmount(new BigDecimal("2000"));
        transfer2.setFromCurrency(currency);
        transfer2.setToCurrency(currency);
        transfer2.setExchangeRate(null);
        transfer2.setCommission(BigDecimal.ZERO);
        transfer2.setStatus(PaymentStatus.COMPLETED);
        transfer2.setCreatedBy(client);

        // R1-653: filtriranje je sad u JPA upitu (findForClientWithFilters), ne in-memory.
        when(transferRepository.findForClientWithFilters(client, null, null, null))
                .thenReturn(List.of(transfer1, transfer2));

        List<TransferResponseDto> result = transferService.getAllTransfers(client, null, null, null);

        assertThat(result.size()).isEqualTo(2);
        assertThat(result.get(0).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }
}
