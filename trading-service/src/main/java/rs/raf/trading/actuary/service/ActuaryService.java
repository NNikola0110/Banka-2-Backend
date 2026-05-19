package rs.raf.trading.actuary.service;

import rs.raf.trading.actuary.dto.ActuaryInfoDto;
import rs.raf.trading.actuary.dto.UpdateActuaryLimitDto;

import java.util.List;

public interface ActuaryService {

    List<ActuaryInfoDto> getAgents(String email, String firstName, String lastName, String position);

    ActuaryInfoDto getActuaryInfo(Long employeeId);

    ActuaryInfoDto updateAgentLimit(Long employeeId, UpdateActuaryLimitDto dto);

    ActuaryInfoDto resetUsedLimit(Long employeeId);

    void resetAllUsedLimits();
}
