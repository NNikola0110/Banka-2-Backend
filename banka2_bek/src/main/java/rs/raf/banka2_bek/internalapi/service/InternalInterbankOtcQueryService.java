package rs.raf.banka2_bek.internalapi.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.InterbankOtcExercisedDto;
import rs.raf.banka2_bek.interbank.model.InterbankOtcContract;
import rs.raf.banka2_bek.interbank.model.InterbankOtcContractStatus;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcContractRepository;

import java.util.List;

/**
 * P2-tax-interbank-otc-1 — read-only izlaganje EXERCISED inter-bank OTC
 * ugovora trading-service tax engine-u.
 *
 * <p>Inter-bank OTC ugovori ({@code interbank_otc_contracts}) zive u banka-core,
 * koji NEMA tax modul. Tax engine je u trading-service-u i bez ovog seam-a nikad
 * nije video inter-bank EXERCISED ugovore → lokalni CLIENT koji exercise-uje
 * inter-bank opciju realizuje kapitalnu dobit koju niko ne oporezuje
 * (under-taxation). Ovaj servis mapira EXERCISED ugovore u
 * {@link InterbankOtcExercisedDto} koji trading-service ukljucuje u 15% obracun.
 *
 * <p>READ-ONLY: ne menja stanje, ne dira 2PC wire-protokol (NEW_TX/COMMIT_TX/
 * ROLLBACK_TX). Cisto cita {@code interbank_otc_contracts}.
 */
@Service
public class InternalInterbankOtcQueryService {

    private final InterbankOtcContractRepository contractRepository;

    public InternalInterbankOtcQueryService(InterbankOtcContractRepository contractRepository) {
        this.contractRepository = contractRepository;
    }

    /**
     * Vraca sve EXERCISED inter-bank OTC ugovore sa lokalnom stranom, mapirane
     * u {@link InterbankOtcExercisedDto}. Trading-service tax engine filtrira
     * CLIENT-ove (EMPLOYEE izuzet BE-ORD-06) i razresava {@code listingId} po
     * ticker-u.
     */
    @Transactional(readOnly = true)
    public List<InterbankOtcExercisedDto> findExercised() {
        return contractRepository.findByStatus(InterbankOtcContractStatus.EXERCISED)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private InterbankOtcExercisedDto toDto(InterbankOtcContract c) {
        return new InterbankOtcExercisedDto(
                c.getId(),
                c.getLocalPartyId(),
                c.getLocalPartyRole(),
                c.getLocalPartyType() != null ? c.getLocalPartyType().name() : null,
                c.getTicker(),
                c.getQuantity(),
                c.getStrikePrice(),
                c.getStrikeCurrency(),
                c.getPremium(),
                c.getPremiumCurrency(),
                c.getExercisedAt());
    }
}
