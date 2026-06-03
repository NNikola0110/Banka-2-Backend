package rs.raf.banka2_bek.internalapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2.contracts.internal.InterbankOtcExercisedDto;
import rs.raf.banka2_bek.interbank.model.InterbankOtcContract;
import rs.raf.banka2_bek.interbank.model.InterbankOtcContractStatus;
import rs.raf.banka2_bek.interbank.model.InterbankPartyType;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcContractRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-tax-interbank-otc-1 — unit test za internal read service koji izlaze
 * EXERCISED inter-bank OTC ugovore trading-service tax engine-u.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalInterbankOtcQueryService")
class InternalInterbankOtcQueryServiceTest {

    @Mock
    private InterbankOtcContractRepository contractRepository;

    @InjectMocks
    private InternalInterbankOtcQueryService service;

    private InterbankOtcContract contract(Long id, InterbankPartyType type, String role,
                                          String ticker, String qty, String strike, String premium) {
        InterbankOtcContract c = new InterbankOtcContract();
        c.setId(id);
        c.setSourceNegotiationId(id);
        c.setLocalPartyType(type);
        c.setLocalPartyId(7L);
        c.setLocalPartyRole(role);
        c.setForeignPartyRoutingNumber(111);
        c.setForeignPartyIdString("partner-x");
        c.setTicker(ticker);
        c.setQuantity(new BigDecimal(qty));
        c.setStrikePrice(new BigDecimal(strike));
        c.setStrikeCurrency("USD");
        c.setPremium(new BigDecimal(premium));
        c.setPremiumCurrency("USD");
        c.setSettlementDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(10));
        c.setStatus(InterbankOtcContractStatus.EXERCISED);
        c.setExercisedAt(LocalDateTime.now());
        return c;
    }

    @Test
    @DisplayName("findExercised queries by EXERCISED status and maps all fields")
    void findExercised_mapsFields() {
        InterbankOtcContract seller = contract(1L, InterbankPartyType.SELLER, "CLIENT",
                "AAPL", "10", "100", "150");

        when(contractRepository.findByStatus(InterbankOtcContractStatus.EXERCISED))
                .thenReturn(List.of(seller));

        List<InterbankOtcExercisedDto> result = service.findExercised();

        verify(contractRepository).findByStatus(InterbankOtcContractStatus.EXERCISED);
        assertThat(result).hasSize(1);
        InterbankOtcExercisedDto dto = result.get(0);
        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.localPartyId()).isEqualTo(7L);
        assertThat(dto.localPartyRole()).isEqualTo("CLIENT");
        assertThat(dto.localPartyType()).isEqualTo("SELLER");
        assertThat(dto.ticker()).isEqualTo("AAPL");
        assertThat(dto.quantity()).isEqualByComparingTo("10");
        assertThat(dto.strikePrice()).isEqualByComparingTo("100");
        assertThat(dto.strikeCurrency()).isEqualTo("USD");
        assertThat(dto.premium()).isEqualByComparingTo("150");
        assertThat(dto.premiumCurrency()).isEqualTo("USD");
        assertThat(dto.exercisedAt()).isNotNull();
    }

    @Test
    @DisplayName("findExercised returns empty when no EXERCISED contracts")
    void findExercised_empty() {
        when(contractRepository.findByStatus(InterbankOtcContractStatus.EXERCISED))
                .thenReturn(List.of());

        assertThat(service.findExercised()).isEmpty();
    }

    @Test
    @DisplayName("findExercised maps BUYER party type and EMPLOYEE role verbatim (filtering is trading-side)")
    void findExercised_mapsBuyerAndEmployee() {
        InterbankOtcContract buyer = contract(2L, InterbankPartyType.BUYER, "EMPLOYEE",
                "MSFT", "5", "200", "80");

        when(contractRepository.findByStatus(InterbankOtcContractStatus.EXERCISED))
                .thenReturn(List.of(buyer));

        List<InterbankOtcExercisedDto> result = service.findExercised();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).localPartyType()).isEqualTo("BUYER");
        assertThat(result.get(0).localPartyRole()).isEqualTo("EMPLOYEE");
    }
}
