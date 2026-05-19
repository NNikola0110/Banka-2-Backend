package rs.raf.trading.actuary.mapper;

import org.junit.jupiter.api.Test;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.actuary.dto.ActuaryInfoDto;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NAPOMENA (copy-first ekstrakcija, faza 2c): mapper sad prima pre-razreseni
 * {@link InternalUserDto} (umesto da cita {@code @OneToOne Employee} vezu).
 * {@code employeePosition} se ne testira jer {@code InternalUserDto} ne nosi
 * {@code position} — vidi STATUS izvestaj 2c-C1.
 */
class ActuaryMapperTest {

    private static InternalUserDto employee(Long id, String firstName, String lastName, String email) {
        return new InternalUserDto(id, "EMPLOYEE", email, firstName, lastName, true);
    }

    @Test
    void toDto_null_returnsNull() {
        assertThat(ActuaryMapper.toDto(null, null)).isNull();
    }

    @Test
    void toDto_withEmployee_mapsAllFields() {
        InternalUserDto employee = employee(10L, "Marko", "Petrovic", "marko@banka.rs");

        ActuaryInfo info = new ActuaryInfo();
        info.setId(1L);
        info.setEmployeeId(10L);
        info.setActuaryType(ActuaryType.AGENT);
        info.setDailyLimit(BigDecimal.valueOf(50000));
        info.setUsedLimit(BigDecimal.valueOf(10000));
        info.setNeedApproval(true);

        ActuaryInfoDto dto = ActuaryMapper.toDto(info, employee);

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getEmployeeId()).isEqualTo(10L);
        assertThat(dto.getEmployeeName()).isEqualTo("Marko Petrovic");
        assertThat(dto.getEmployeeEmail()).isEqualTo("marko@banka.rs");
        assertThat(dto.getActuaryType()).isEqualTo("AGENT");
        assertThat(dto.getDailyLimit()).isEqualByComparingTo(BigDecimal.valueOf(50000));
        assertThat(dto.getUsedLimit()).isEqualByComparingTo(BigDecimal.valueOf(10000));
        assertThat(dto.isNeedApproval()).isTrue();
    }

    @Test
    void toDto_withoutResolvedEmployee_employeeNameAndEmailAreNull() {
        ActuaryInfo info = new ActuaryInfo();
        info.setId(2L);
        info.setEmployeeId(20L);
        info.setActuaryType(ActuaryType.SUPERVISOR);
        info.setDailyLimit(null);
        info.setUsedLimit(null);
        info.setNeedApproval(false);

        // Employee se nije mogao razresiti (banka-core nedostupan / 404).
        ActuaryInfoDto dto = ActuaryMapper.toDto(info, null);

        assertThat(dto.getId()).isEqualTo(2L);
        // employeeId se i dalje puni iz lokalne kolone.
        assertThat(dto.getEmployeeId()).isEqualTo(20L);
        assertThat(dto.getEmployeeName()).isNull();
        assertThat(dto.getEmployeeEmail()).isNull();
        assertThat(dto.getActuaryType()).isEqualTo("SUPERVISOR");
        assertThat(dto.isNeedApproval()).isFalse();
    }

    @Test
    void toDto_nullActuaryType_returnsNullType() {
        ActuaryInfo info = new ActuaryInfo();
        info.setId(3L);
        info.setActuaryType(null);

        ActuaryInfoDto dto = ActuaryMapper.toDto(info, null);

        assertThat(dto.getActuaryType()).isNull();
    }
}
