package rs.raf.banka2_bek.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.banka2_bek.audit.service.CurrentAuditActorResolver;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class CurrentAuditActorResolverTest {

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private ClientRepository clientRepository;

    @InjectMocks
    private CurrentAuditActorResolver resolver;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "n/a", List.of()));
    }

    @Test
    void resolvesEmployeeExecutor_R5_1891() {
        Employee emp = Employee.builder().id(99L).firstName("Nikola").lastName("Milenkovic").build();
        lenient().when(employeeRepository.findByEmail("nikola.milenkovic@banka.rs"))
                .thenReturn(Optional.of(emp));
        authenticateAs("nikola.milenkovic@banka.rs");

        CurrentAuditActorResolver.AuditActor actor = resolver.resolveCurrentActor();

        assertThat(actor.actorId()).isEqualTo(99L);
        assertThat(actor.actorType()).isEqualTo("EMPLOYEE");
    }

    @Test
    void resolvesClientExecutor_whenNotEmployee_R5_1891() {
        Client client = new Client();
        client.setId(42L);
        lenient().when(employeeRepository.findByEmail("stefan.jovanovic@gmail.com"))
                .thenReturn(Optional.empty());
        lenient().when(clientRepository.findByEmail("stefan.jovanovic@gmail.com"))
                .thenReturn(Optional.of(client));
        authenticateAs("stefan.jovanovic@gmail.com");

        CurrentAuditActorResolver.AuditActor actor = resolver.resolveCurrentActor();

        assertThat(actor.actorId()).isEqualTo(42L);
        assertThat(actor.actorType()).isEqualTo("CLIENT");
    }

    @Test
    void fallsBackToSystem_whenNoAuthContext() {
        // Bez SecurityContext-a (interni/scheduler poziv) → SYSTEM aktor.
        CurrentAuditActorResolver.AuditActor actor = resolver.resolveCurrentActor();

        assertThat(actor.actorId()).isEqualTo(0L);
        assertThat(actor.actorType()).isEqualTo("SYSTEM");
    }

    @Test
    void fallsBackToSystem_whenEmailMatchesNoUser() {
        lenient().when(employeeRepository.findByEmail("ghost@nowhere.rs")).thenReturn(Optional.empty());
        lenient().when(clientRepository.findByEmail("ghost@nowhere.rs")).thenReturn(Optional.empty());
        authenticateAs("ghost@nowhere.rs");

        CurrentAuditActorResolver.AuditActor actor = resolver.resolveCurrentActor();

        assertThat(actor.actorType()).isEqualTo("SYSTEM");
    }
}
