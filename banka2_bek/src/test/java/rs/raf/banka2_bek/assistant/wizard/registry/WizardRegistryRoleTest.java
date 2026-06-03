package rs.raf.banka2_bek.assistant.wizard.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.assistant.wizard.model.WizardTemplate;
import rs.raf.banka2_bek.assistant.wizard.service.SlotResolvers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-B8 N3: verifikuje role gate na wizard template-ima — kriticno za
 * privilege-escalation (unblock_card mora biti EMPLOYEE-only). Registracija
 * slot-ova ne poziva resolvere (lambdas se zovu tek na runtime), pa je mock
 * {@link SlotResolvers} dovoljan da {@code init()} popuni template-e.
 */
@ExtendWith(MockitoExtension.class)
class WizardRegistryRoleTest {

    @Mock private SlotResolvers resolvers;

    private WizardRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WizardRegistry(resolvers);
        registry.init(); // package-private @PostConstruct
    }

    @Test
    @DisplayName("unblock_card wizard je EMPLOYEE-only (CLIENT ne sme da odblokira)")
    void unblockCard_employeeOnly() {
        WizardTemplate tpl = registry.get("unblock_card").orElseThrow();
        assertThat(tpl.allowedRoles()).containsExactly("EMPLOYEE");
        assertThat(tpl.allowedRoles()).doesNotContain("CLIENT");
    }

    @Test
    @DisplayName("block_card wizard je i dalje dostupan CLIENT-u (vlasnik sme da blokira)")
    void blockCard_clientAllowed() {
        WizardTemplate tpl = registry.get("block_card").orElseThrow();
        assertThat(tpl.allowedRoles()).contains("CLIENT");
    }
}
