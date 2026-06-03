package rs.raf.trading.otc.saga.support;

import jakarta.persistence.EntityManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.otc.model.OtcContractStatus;
import rs.raf.trading.otc.repository.OtcContractRepository;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imutabilni snapshot relevantnog stanja sistema (novac + akcije + status
 * ugovora) za poredjenje pre/posle SAGA exercise-a u W2 invarijant testovima.
 *
 * <p><b>Sveze citanje (kriticno):</b> {@link #capture} prvo {@code flush()+clear()}
 * persistence context (da se odbace kesirane/stale entity instance iz prethodnih
 * cita test-transakcije), pa ponovo {@code findById}-uje iz baze — tako snapshot
 * odrazava COMMIT-ovano stanje koje je SAGA upisala u svojoj (zasebnoj, server-side)
 * transakciji, a ne stale verziju iz test persistence context-a.
 *
 * @param totalMoney      Σ balance preko svih posmatranih racuna (invarijanta I1)
 * @param balances        accountId -> balance (per-account novcana fidelnost)
 * @param totalShares     ukupan broj akcija ovog listinga preko buyer+seller (I2)
 * @param sharesByUser    "role|userId" -> quantity (per-vlasnik akcije)
 * @param reservedShares  "role|userId" -> reservedQuantity (I3 seller hold release)
 * @param contractStatus  status ugovora (I6 ACTIVE/EXERCISED)
 */
public record StateSnapshot(BigDecimal totalMoney,
                            Map<Long, BigDecimal> balances,
                            int totalShares,
                            Map<String, Integer> sharesByUser,
                            Map<String, Integer> reservedShares,
                            OtcContractStatus contractStatus) {

    /** Identifikuje vlasnika portfolija za sharesByUser/reservedShares mape. */
    public record OwnerKey(Long userId, String userRole, Long listingId) {
        String mapKey() {
            return userRole + "|" + userId;
        }
    }

    /**
     * Snima SVEZE stanje iz baze + fake banke.
     *
     * <p>Ako je transakcija aktivna (npr. {@code @Transactional} recovery test),
     * radi {@code flush()+clear()} da odbaci stale persistence-context entitete.
     * Ako NIJE (HTTP-driven testovi gde SAGA komituje server-side, a test nema
     * svoju transakciju), svaki repo poziv ionako otvara novu sesiju i cita
     * sveze iz baze — {@code flush()} bi bacio {@code TransactionRequired}, pa se
     * preskace. Oba puta rezultat odrazava COMMIT-ovano stanje.
     *
     * @param em             entity manager test-konteksta (za flush+clear)
     * @param fake           in-memory banka (za balance/totalMoney)
     * @param portfolioRepo  portfolio repo (za sveze share quantity)
     * @param contractRepo   ugovor repo (za sveze status)
     * @param contractId     id posmatranog ugovora
     * @param owners         vlasnici cije akcije + rezervacije pratimo
     * @param accountIds     racuni cije balance + totalMoney pratimo
     */
    public static StateSnapshot capture(EntityManager em,
                                        FakeBankaCoreClient fake,
                                        PortfolioRepository portfolioRepo,
                                        OtcContractRepository contractRepo,
                                        Long contractId,
                                        List<OwnerKey> owners,
                                        List<Long> accountIds) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            em.flush();
            em.clear();
        }

        Map<Long, BigDecimal> balances = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Long id : accountIds) {
            BigDecimal bal = fake.balanceOf(id);
            balances.put(id, bal);
            total = total.add(bal);
        }

        Map<String, Integer> sharesByUser = new LinkedHashMap<>();
        Map<String, Integer> reservedShares = new LinkedHashMap<>();
        int totalShares = 0;
        for (OwnerKey o : owners) {
            Portfolio p = portfolioRepo
                    .findByUserIdAndUserRoleAndListingId(o.userId(), o.userRole(), o.listingId())
                    .orElse(null);
            int qty = p == null ? 0 : (p.getQuantity() == null ? 0 : p.getQuantity());
            int reserved = p == null ? 0 : (p.getReservedQuantity() == null ? 0 : p.getReservedQuantity());
            sharesByUser.put(o.mapKey(), qty);
            reservedShares.put(o.mapKey(), reserved);
            totalShares += qty;
        }

        OtcContract contract = contractRepo.findById(contractId).orElseThrow();
        return new StateSnapshot(total, balances, totalShares, sharesByUser,
                reservedShares, contract.getStatus());
    }
}
