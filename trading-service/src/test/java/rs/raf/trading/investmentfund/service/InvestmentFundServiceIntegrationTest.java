package rs.raf.trading.investmentfund.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.investmentfund.dto.InvestmentFundDtos.*;
import rs.raf.trading.investmentfund.repository.ClientFundPositionRepository;
import rs.raf.trading.investmentfund.repository.ClientFundTransactionRepository;
import rs.raf.trading.investmentfund.repository.FundValueSnapshotRepository;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.security.TradingUserResolver;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Integracioni test {@link InvestmentFundService} — pun Spring kontekst
 * (H2 test profil), realan {@code InvestmentFundService} + JPA persistencija
 * ({@code InvestmentFund} / {@code FundValueSnapshot} su trading-service entiteti).
 *
 * NAPOMENA (faza 2c): monolitni test je seedovao {@code Currency}/{@code Company}/
 * {@code Employee}/{@code ActuaryInfo} i izvrsavao {@code createFund} koji je
 * gradio pun {@code Account} preko {@code AccountRepository}. trading-service NEMA
 * banka-core entitete — {@code createFund} provizionira fond racun preko
 * banka-core internog API-ja, pa je {@link BankaCoreClient} {@code @MockitoBean}
 * stubovan da vrati {@link InternalAccountDto} (18-cifren broj racuna).
 * {@link TradingUserResolver} (identitet) je takodje {@code @MockitoBean}.
 * {@code ActuaryInfo} se seeduje direktno preko repozitorijuma sa soft
 * {@code employeeId}-jem.
 */
@SpringBootTest
@ActiveProfiles("test")
class InvestmentFundServiceIntegrationTest {

    private static final Long SUPERVISOR_ID = 9101L;
    private static final String SUPERVISOR_NAME = "Nikola Stamenkovic";

    @Autowired private InvestmentFundService investmentFundService;
    @Autowired private InvestmentFundRepository investmentFundRepository;
    @Autowired private FundValueSnapshotRepository fundValueSnapshotRepository;
    @Autowired private ClientFundPositionRepository clientFundPositionRepository;
    @Autowired private ClientFundTransactionRepository clientFundTransactionRepository;
    @Autowired private ActuaryInfoRepository actuaryInfoRepository;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

    @MockitoBean
    private TradingUserResolver tradingUserResolver;

    /** Generator jedinstvenih fond-racun id-eva za stubovan provisionFundAccount. */
    private final AtomicLong accountIdSeq = new AtomicLong(70_001);

    @BeforeEach
    void setUp() {
        clientFundTransactionRepository.deleteAll();
        clientFundPositionRepository.deleteAll();
        fundValueSnapshotRepository.deleteAll();
        investmentFundRepository.deleteAll();
        actuaryInfoRepository.deleteAll();

        ActuaryInfo supervisor = new ActuaryInfo();
        supervisor.setEmployeeId(SUPERVISOR_ID);
        supervisor.setActuaryType(ActuaryType.SUPERVISOR);
        supervisor.setNeedApproval(false);
        actuaryInfoRepository.save(supervisor);

        // provisionFundAccount: svaki poziv vraca nov FUND racun sa 18-cifrenim
        // brojem racuna i jedinstvenim id-jem.
        lenient().when(bankaCoreClient.provisionFundAccount(anyString(), anyLong()))
                .thenAnswer(inv -> {
                    long id = accountIdSeq.getAndIncrement();
                    return fundAccount(id, "RSD", BigDecimal.ZERO);
                });
        // getAccount: getFundDetails cita racun fonda — vraca racun sa istim id-jem.
        lenient().when(bankaCoreClient.getAccount(anyLong()))
                .thenAnswer(inv -> fundAccount(inv.getArgument(0), "RSD", BigDecimal.ZERO));
        lenient().when(tradingUserResolver.resolveName(anyLong(), anyString()))
                .thenReturn(SUPERVISOR_NAME);
    }

    @AfterEach
    void tearDown() {
        clientFundTransactionRepository.deleteAll();
        clientFundPositionRepository.deleteAll();
        fundValueSnapshotRepository.deleteAll();
        investmentFundRepository.deleteAll();
        actuaryInfoRepository.deleteAll();
    }

    private InternalAccountDto fundAccount(Long id, String currency, BigDecimal balance) {
        return new InternalAccountDto(id, accountNumberFor(id), "Banka 2 d.o.o.",
                balance, balance, BigDecimal.ZERO, currency, "ACTIVE",
                null, null, "FUND");
    }

    /** 18-cifren broj racuna izveden iz id-ja (za assert na duzinu). */
    private String accountNumberFor(Long id) {
        String suffix = String.valueOf(id);
        return ("222000100000000000" + suffix).substring(suffix.length());
    }

    @Test
    @DisplayName("IT: kreiraj fond POST, povuci ga GET - osnovni flow")
    void createFund_thenGetById_returnsCorrectData() {
        CreateFundDto dto = new CreateFundDto(
                "Alpha Growth Fund",
                "Fond fokusiran na IT sektor",
                new BigDecimal("1000.00"));

        InvestmentFundDetailDto created = investmentFundService.createFund(dto, SUPERVISOR_ID);

        assertNotNull(created);
        assertNotNull(created.getId());
        assertEquals("Alpha Growth Fund", created.getName());
        assertEquals("Fond fokusiran na IT sektor", created.getDescription());
        assertEquals(0, new BigDecimal("1000.00").compareTo(created.getMinimumContribution()));
        assertEquals(SUPERVISOR_NAME, created.getManagerName());
        assertNotNull(created.getAccountNumber());
        assertEquals(18, created.getAccountNumber().length());
        assertEquals(0, BigDecimal.ZERO.compareTo(created.getFundValue()));
        assertEquals(0, BigDecimal.ZERO.compareTo(created.getLiquidAmount()));
        assertTrue(created.getHoldings().isEmpty());

        InvestmentFundDetailDto fetched = investmentFundService.getFundDetails(created.getId());

        assertEquals(created.getId(), fetched.getId());
        assertEquals("Alpha Growth Fund", fetched.getName());
        assertEquals(created.getAccountNumber(), fetched.getAccountNumber());
        assertEquals(1, fetched.getPerformance().size());

        assertTrue(investmentFundRepository.findById(created.getId()).isPresent());
    }

    @Test
    @DisplayName("IT: listDiscovery vraca kreirani fond")
    void createFund_thenList_fundAppearsInDiscovery() {
        CreateFundDto dto = new CreateFundDto("Beta Fund", "Opis beta fonda", new BigDecimal("500.00"));
        investmentFundService.createFund(dto, SUPERVISOR_ID);

        List<InvestmentFundSummaryDto> list = investmentFundService.listDiscovery(null, null, null);

        assertEquals(1, list.size());
        assertEquals("Beta Fund", list.get(0).getName());
        assertEquals(SUPERVISOR_NAME, list.get(0).getManagerName());
    }

    @Test
    @DisplayName("IT: search filter radi - vraca samo fond koji matchuje")
    void listDiscovery_withSearch_returnsOnlyMatching() {
        investmentFundService.createFund(
                new CreateFundDto("Alpha Tech", "IT fond", new BigDecimal("1000")), SUPERVISOR_ID);
        investmentFundService.createFund(
                new CreateFundDto("Beta Energy", "Energetski fond", new BigDecimal("2000")), SUPERVISOR_ID);

        List<InvestmentFundSummaryDto> result = investmentFundService.listDiscovery("alpha", null, null);

        assertEquals(1, result.size());
        assertEquals("Alpha Tech", result.get(0).getName());
    }

    @Test
    @DisplayName("IT: duplikat imena baca IllegalArgumentException")
    void createFund_duplicateName_throwsException() {
        CreateFundDto dto = new CreateFundDto("Unique Fund", "Opis", new BigDecimal("1000"));
        investmentFundService.createFund(dto, SUPERVISOR_ID);

        assertThrows(IllegalArgumentException.class,
                () -> investmentFundService.createFund(dto, SUPERVISOR_ID));
    }

    @Test
    @DisplayName("IT: getFundDetails za nepostojeci id baca EntityNotFoundException")
    void getFundDetails_nonExistent_throwsEntityNotFound() {
        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> investmentFundService.getFundDetails(9999L));
    }

    @Test
    @DisplayName("IT: createFund odbija ne-supervizora (nema ActuaryInfo) sa IllegalStateException")
    void createFund_nonSupervisor_throwsIllegalState() {
        CreateFundDto dto = new CreateFundDto("Gamma Fund", "Opis", new BigDecimal("1000"));

        // employeeId 8888 nije seedovan u actuary_info -> nije supervizor
        assertThrows(IllegalStateException.class,
                () -> investmentFundService.createFund(dto, 8888L));
    }
}
