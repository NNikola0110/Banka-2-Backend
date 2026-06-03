package rs.raf.banka2_bek.internalapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Testovi za {@link InternalLookupService#getUserPermissions(String)} sa fokusom
 * na P0-2 fix: klijent sa {@code canTradeStocks=true} mora razresiti
 * {@code TRADE_STOCKS} permisiju (inace svaki klijent dobija 403 na POST /orders).
 */
@ExtendWith(MockitoExtension.class)
class InternalLookupServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ClientRepository clientRepository;

    @InjectMocks private InternalLookupService service;

    @Test
    @DisplayName("getUserPermissions - klijent sa canTradeStocks=true vraca [TRADE_STOCKS]")
    void getUserPermissions_clientWithCanTradeStocks_returnsTradeStocks() {
        String email = "stefan.jovanovic@gmail.com";
        when(employeeRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.empty());
        Client client = Client.builder()
                .id(1L)
                .email(email)
                .firstName("Stefan")
                .lastName("Jovanovic")
                .canTradeStocks(true)
                .build();
        when(clientRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.of(client));

        List<String> permissions = service.getUserPermissions(email);

        assertThat(permissions).containsExactly("TRADE_STOCKS");
    }

    @Test
    @DisplayName("getUserPermissions - klijent sa canTradeStocks=false vraca praznu listu")
    void getUserPermissions_clientWithoutCanTradeStocks_returnsEmpty() {
        String email = "ana.stojanovic@hotmail.com";
        when(employeeRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.empty());
        Client client = Client.builder()
                .id(2L)
                .email(email)
                .firstName("Ana")
                .lastName("Stojanovic")
                .canTradeStocks(false)
                .build();
        when(clientRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.of(client));

        List<String> permissions = service.getUserPermissions(email);

        assertThat(permissions).isEmpty();
    }

    @Test
    @DisplayName("getUserPermissions - zaposleni vraca svoje permisije (regression guard)")
    void getUserPermissions_employee_unchanged() {
        String email = "nikola.milenkovic@banka.rs";
        Employee employee = new Employee();
        employee.setEmail(email);
        employee.setPermissions(Set.of("SUPERVISOR", "TRADE_STOCKS"));
        when(employeeRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.of(employee));
        // clientRepository se ne sme ni dotaci kad postoji zaposleni (employee-first)
        lenient().when(clientRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.empty());

        List<String> permissions = service.getUserPermissions(email);

        assertThat(permissions).containsExactlyInAnyOrder("SUPERVISOR", "TRADE_STOCKS");
    }

    @Test
    @DisplayName("getUserPermissions - nepoznat email (ni zaposleni ni klijent) vraca praznu listu")
    void getUserPermissions_unknownEmail_returnsEmpty() {
        String email = "nepostoji@nigde.rs";
        when(employeeRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.empty());
        when(clientRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.empty());

        List<String> permissions = service.getUserPermissions(email);

        assertThat(permissions).isEmpty();
    }

    // -- OT-1061: getSupervisorIds (trading razresava primaoce tax-FX-fail notifikacije) --

    @Test
    @DisplayName("OT-1061 - getSupervisorIds delegira na repo (SUPERVISOR permisija) i vraca id-eve")
    void getSupervisorIds_returnsActiveSupervisorIds() {
        when(employeeRepository.findActiveEmployeeIdsByPermission("SUPERVISOR"))
                .thenReturn(List.of(3L, 7L));

        List<Long> ids = service.getSupervisorIds();

        assertThat(ids).containsExactly(3L, 7L);
    }

    @Test
    @DisplayName("OT-1061 - getSupervisorIds bez supervizora vraca praznu listu (ne null)")
    void getSupervisorIds_noSupervisors_returnsEmpty() {
        when(employeeRepository.findActiveEmployeeIdsByPermission("SUPERVISOR"))
                .thenReturn(List.of());

        assertThat(service.getSupervisorIds()).isEmpty();
    }

    // -- R1 403: case-insensitive email lookup (klijent NE sme da izgubi TRADE_STOCKS) --

    @Test
    @DisplayName("R1 403 - getUserPermissions razresava klijenta i kad se case email-a razlikuje")
    void getUserPermissions_clientUpperCaseEmail_stillResolvesTradeStocks() {
        // JWT subject dolazi UPPERCASE; baza cuva lowercase (ignore-case repo to premoscuje)
        String jwtEmail = "STEFAN.JOVANOVIC@GMAIL.COM";
        when(employeeRepository.findByEmailIgnoreCase(jwtEmail)).thenReturn(Optional.empty());
        Client client = Client.builder()
                .id(1L)
                .email("stefan.jovanovic@gmail.com")
                .firstName("Stefan")
                .lastName("Jovanovic")
                .canTradeStocks(true)
                .build();
        when(clientRepository.findByEmailIgnoreCase(jwtEmail)).thenReturn(Optional.of(client));

        List<String> permissions = service.getUserPermissions(jwtEmail);

        // Pre fix-a (exact findByEmail) ovo bi bilo prazno → 403 na POST /orders.
        assertThat(permissions).containsExactly("TRADE_STOCKS");
    }

    @Test
    @DisplayName("R1 403 - getUserByEmail razresava klijenta nezavisno od case-a email-a")
    void getUserByEmail_mixedCaseEmail_resolvesClient() {
        String jwtEmail = "Stefan.Jovanovic@Gmail.Com";
        Client client = Client.builder()
                .id(7L)
                .email("stefan.jovanovic@gmail.com")
                .firstName("Stefan")
                .lastName("Jovanovic")
                .active(true)
                .build();
        when(clientRepository.findByEmailIgnoreCase(jwtEmail)).thenReturn(Optional.of(client));

        var dto = service.getUserByEmail(jwtEmail);

        assertThat(dto.userId()).isEqualTo(7L);
        assertThat(dto.userRole()).isEqualTo("CLIENT");
    }

    // -- R1 402: nepoznata {userRole} putanja je BAD_REQUEST (UnknownUserRoleException → 400), ne 404 --

    @Test
    @DisplayName("R1 402 - getUserById sa nepoznatom rolom baca UnknownUserRoleException (mapira se na 400)")
    void getUserById_unknownRole_throwsUnknownUserRole() {
        assertThat(
                org.junit.jupiter.api.Assertions.assertThrows(
                        UnknownUserRoleException.class,
                        () -> service.getUserById("ROBOT", 1L)))
                .hasMessageContaining("Unknown user role");
        // ostaje IllegalArgumentException podtip (ocuvana semantika)
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.getUserById("ROBOT", 1L));
    }

    @Test
    @DisplayName("R1 402 - getUserById sa CLIENT rolom ali nepostojecim id-em baca obican IAE (→404 not-found)")
    void getUserById_clientNotFound_throwsPlainIllegalArgument_not404Role() {
        when(clientRepository.findById(99L)).thenReturn(Optional.empty());
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.getUserById("CLIENT", 99L));
        // NIJE UnknownUserRoleException → handler ga ostavlja na 404 (not-found semantika)
        assertThat(ex).isNotInstanceOf(UnknownUserRoleException.class);
        assertThat(ex).hasMessageContaining("Client not found");
    }

    // -- R1 713: sistemski racuni (FUND/BANK_TRADING) → eksplicitan label, ne "Unknown" --

    @Test
    @DisplayName("R1 713 - getAccount za FUND sistemski racun vraca eksplicitan label umesto 'Unknown'")
    void getAccount_fundSystemAccount_resolvesSystemOwnerLabel() {
        rs.raf.banka2_bek.currency.model.Currency rsd = new rs.raf.banka2_bek.currency.model.Currency();
        rsd.setCode("RSD");
        rs.raf.banka2_bek.account.model.Account fundAcct = new rs.raf.banka2_bek.account.model.Account();
        fundAcct.setId(50L);
        fundAcct.setAccountNumber("222FUND0001");
        fundAcct.setBalance(java.math.BigDecimal.TEN);
        fundAcct.setAvailableBalance(java.math.BigDecimal.TEN);
        fundAcct.setReservedAmount(java.math.BigDecimal.ZERO);
        fundAcct.setCurrency(rsd);
        fundAcct.setStatus(rs.raf.banka2_bek.account.model.AccountStatus.ACTIVE);
        fundAcct.setAccountCategory(rs.raf.banka2_bek.account.model.AccountCategory.FUND);
        // bez client/company (sistemski racun)
        when(accountRepository.findById(50L)).thenReturn(Optional.of(fundAcct));

        var dto = service.getAccount(50L);

        assertThat(dto.ownerName()).isEqualTo("Investicioni fond (sistemski)");
        assertThat(dto.ownerName()).isNotEqualTo("Unknown");
    }
}
