package rs.raf.banka2_bek.branch.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.branch.model.Branch;
import rs.raf.banka2_bek.branch.model.BranchType;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-branches-1: {@link BranchRepository#findByFilters} kombinovani filteri/search
 * nad realnom JPA (H2). Pokriva null-safe grane (svaki filter null → ignorisan),
 * type/has24h/hasDriveThrough exact-match, case-insensitive search nad name+address,
 * i ORDER BY (type ASC, name ASC).
 *
 * NAPOMENA: Spring Boot 4 je uklonio @DataJpaTest iz default test-autoconfigure
 * modula — koristimo @SpringBootTest sa H2 (application-test.properties), isti
 * obrazac kao InterbankOtcContractRepositoryTest.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BranchRepositoryTest {

    @Autowired
    private BranchRepository repository;

    @BeforeEach
    void cleanUp() {
        repository.deleteAll();
    }

    private Branch branch(String name, String address) {
        return Branch.builder()
                .name(name).type(BranchType.BRANCH).address(address)
                .latitude(new BigDecimal("44.787000")).longitude(new BigDecimal("20.457000"))
                .openingHours("08-16")
                .has24h(false).hasDriveThrough(false)
                .build();
    }

    private Branch atm(String name, String address, boolean has24h, boolean driveThrough) {
        return Branch.builder()
                .name(name).type(BranchType.ATM).address(address)
                .latitude(new BigDecimal("44.800000")).longitude(new BigDecimal("20.470000"))
                .openingHours("00-24")
                .has24h(has24h).hasDriveThrough(driveThrough)
                .build();
    }

    @Test
    @DisplayName("All filters null → returns all, ordered by type then name")
    void allNull_returnsAllOrdered() {
        // BRANCH < ATM? Enum string ASC: "ATM" < "BRANCH" leksikografski.
        repository.save(branch("Vracar Filijala", "Njegoseva 1"));
        repository.save(atm("Zeleni Venac ATM", "Brankova 5", true, false));
        repository.save(atm("Slavija ATM", "Beogradska 10", false, false));

        List<Branch> all = repository.findByFilters(null, null, null, null);

        assertThat(all).hasSize(3);
        // type ASC: ATM-ovi pre BRANCH-a; unutar ATM-a name ASC.
        assertThat(all.get(0).getType()).isEqualTo(BranchType.ATM);
        assertThat(all.get(0).getName()).isEqualTo("Slavija ATM");
        assertThat(all.get(1).getName()).isEqualTo("Zeleni Venac ATM");
        assertThat(all.get(2).getType()).isEqualTo(BranchType.BRANCH);
    }

    @Test
    @DisplayName("type filter → only matching type")
    void typeFilter_onlyMatching() {
        repository.save(branch("Filijala A", "Adr A"));
        repository.save(atm("ATM B", "Adr B", true, false));

        List<Branch> branches = repository.findByFilters(BranchType.BRANCH, null, null, null);
        List<Branch> atms = repository.findByFilters(BranchType.ATM, null, null, null);

        assertThat(branches).hasSize(1);
        assertThat(branches.get(0).getName()).isEqualTo("Filijala A");
        assertThat(atms).hasSize(1);
        assertThat(atms.get(0).getName()).isEqualTo("ATM B");
    }

    @Test
    @DisplayName("has24h=true filter → only 24h ATMs")
    void has24hFilter_onlyTrue() {
        repository.save(atm("ATM 24h", "Adr1", true, false));
        repository.save(atm("ATM standard", "Adr2", false, false));

        List<Branch> result = repository.findByFilters(null, true, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("ATM 24h");
    }

    @Test
    @DisplayName("hasDriveThrough=true filter → only drive-through ATMs")
    void driveThroughFilter_onlyTrue() {
        repository.save(atm("ATM DT", "Adr1", false, true));
        repository.save(atm("ATM no-DT", "Adr2", false, false));

        List<Branch> result = repository.findByFilters(null, null, true, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("ATM DT");
    }

    @Test
    @DisplayName("search matches case-insensitively on name OR address")
    void search_matchesNameOrAddress_caseInsensitive() {
        repository.save(branch("Vracar Filijala", "Njegoseva 1"));
        repository.save(branch("Novi Beograd", "Bulevar Mihajla Pupina 165"));

        // match na name (case-insensitive)
        assertThat(repository.findByFilters(null, null, null, "vracar")).hasSize(1);
        // match na address (case-insensitive)
        assertThat(repository.findByFilters(null, null, null, "PUPINA")).hasSize(1);
        // bez podudaranja
        assertThat(repository.findByFilters(null, null, null, "nepostojeci")).isEmpty();
    }

    @Test
    @DisplayName("combined type + search filters")
    void combinedTypeAndSearch() {
        repository.save(branch("Centar Filijala", "Knez Mihailova 1"));
        repository.save(atm("Centar ATM", "Knez Mihailova 2", true, false));

        List<Branch> result = repository.findByFilters(BranchType.ATM, null, null, "centar");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo(BranchType.ATM);
        assertThat(result.get(0).getName()).isEqualTo("Centar ATM");
    }
}
