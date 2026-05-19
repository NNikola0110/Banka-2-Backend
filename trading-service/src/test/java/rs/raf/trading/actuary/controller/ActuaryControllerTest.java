package rs.raf.trading.actuary.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.raf.trading.actuary.controller.exception_handler.ActuaryExceptionHandler;
import rs.raf.trading.actuary.dto.ActuaryInfoDto;
import rs.raf.trading.actuary.dto.UpdateActuaryLimitDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.trading.actuary.service.ActuaryService;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;


import static org.hamcrest.Matchers.hasSize;

import org.mockito.ArgumentCaptor;

import static org.hamcrest.Matchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ActuaryControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ActuaryService actuaryService;

    @InjectMocks
    private ActuaryController actuaryController;

    private ActuaryInfoDto testAgentDto;
    private ActuaryInfoDto testAgentDto2;
    private ActuaryInfoDto supervisorDto;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(actuaryController)
                .setControllerAdvice(new ActuaryExceptionHandler())
                .build();

        testAgentDto = new ActuaryInfoDto();
        testAgentDto.setId(1L);
        testAgentDto.setEmployeeId(10L);
        testAgentDto.setEmployeeName("Marko Markovic");
        testAgentDto.setEmployeeEmail("marko.markovic@banka.rs");
        testAgentDto.setEmployeePosition("Menadzer");
        testAgentDto.setActuaryType("AGENT");
        testAgentDto.setDailyLimit(new BigDecimal("100000.00"));
        testAgentDto.setUsedLimit(new BigDecimal("15000.00"));
        testAgentDto.setNeedApproval(false);

        testAgentDto2 = new ActuaryInfoDto();
        testAgentDto2.setId(2L);
        testAgentDto2.setEmployeeId(11L);
        testAgentDto2.setEmployeeName("Jelena Jovanovic");
        testAgentDto2.setEmployeeEmail("jelena.jovanovic@banka.rs");
        testAgentDto2.setEmployeePosition("Analiticar");
        testAgentDto2.setActuaryType("AGENT");
        testAgentDto2.setDailyLimit(new BigDecimal("50000.00"));
        testAgentDto2.setUsedLimit(BigDecimal.ZERO);
        testAgentDto2.setNeedApproval(true);

        supervisorDto = new ActuaryInfoDto();
        supervisorDto.setId(3L);
        supervisorDto.setEmployeeId(20L);
        supervisorDto.setEmployeeName("Nina Nikolic");
        supervisorDto.setEmployeeEmail("nina.nikolic@banka.rs");
        supervisorDto.setEmployeePosition("Direktor");
        supervisorDto.setActuaryType("SUPERVISOR");
        supervisorDto.setNeedApproval(false);
    }

    @Test
    @DisplayName("GET /actuaries/agents - 200 OK sa listom agenata")
    void getAgentsReturnsList() throws Exception {
        when(actuaryService.getAgents(null, null, null, null))
                .thenReturn(List.of(testAgentDto, testAgentDto2));

        mockMvc.perform(get("/actuaries/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].employeeName").value("Marko Markovic"))
                .andExpect(jsonPath("$[1].employeeName").value("Jelena Jovanovic"));

        verify(actuaryService).getAgents(null, null, null, null);
    }

    @Test
    @DisplayName("GET /actuaries/agents - 200 OK sa praznom listom")
    void getAgentsReturnsEmptyList() throws Exception {
        when(actuaryService.getAgents(null, null, null, null))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/actuaries/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /actuaries/agents filtrira po email-u")
    void getAgentsFilteredByEmail() throws Exception {
        when(actuaryService.getAgents(eq("marko"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(testAgentDto));

        mockMvc.perform(get("/actuaries/agents").param("email", "marko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].employeeEmail").value("marko.markovic@banka.rs"));

        verify(actuaryService).getAgents("marko", null, null, null);
    }

    @Test
    @DisplayName("GET /actuaries/{employeeId} - 200 OK sa detaljima aktuara")
    void getActuaryInfoReturnsDto() throws Exception {
        when(actuaryService.getActuaryInfo(10L)).thenReturn(testAgentDto);

        mockMvc.perform(get("/actuaries/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value(10))
                .andExpect(jsonPath("$.employeeName").value("Marko Markovic"));
    }

    @Test
    @DisplayName("GET /actuaries/{employeeId} - 404 kada zapis ne postoji")
    void getActuaryInfoNotFoundReturns404() throws Exception {
        when(actuaryService.getActuaryInfo(999L))
                .thenThrow(new IllegalArgumentException("Actuary info for employee with ID 999 not found."));

        mockMvc.perform(get("/actuaries/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Actuary info for employee with ID 999 not found."));
    }

    @Test
    @DisplayName("PATCH /actuaries/{employeeId}/limit - 200 OK kada se agent uspesno azurira")
    void updateAgentLimitReturnsUpdatedDto() throws Exception {
        UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
        dto.setDailyLimit(new BigDecimal("250000.00"));
        dto.setNeedApproval(true);

        ActuaryInfoDto updated = new ActuaryInfoDto();
        updated.setId(1L);
        updated.setEmployeeId(10L);
        updated.setEmployeeName("Marko Markovic");
        updated.setActuaryType("AGENT");
        updated.setDailyLimit(new BigDecimal("250000.00"));
        updated.setUsedLimit(new BigDecimal("15000.00"));
        updated.setNeedApproval(true);

        when(actuaryService.updateAgentLimit(eq(10L), eq(dto))).thenReturn(updated);

        mockMvc.perform(patch("/actuaries/10/limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyLimit").value(250000.00))
                .andExpect(jsonPath("$.needApproval").value(true))
                .andExpect(jsonPath("$.usedLimit").value(15000.00));
    }

    @Test
    @DisplayName("PATCH /actuaries/{employeeId}/limit - 403 kada pokusava izmena supervizora")
    void updateAgentLimitForSupervisorReturns403() throws Exception {
        UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
        dto.setDailyLimit(new BigDecimal("1000"));

        when(actuaryService.updateAgentLimit(eq(20L), eq(dto)))
                .thenThrow(new IllegalStateException("Only AGENT actuaries can be modified."));

        mockMvc.perform(patch("/actuaries/20/limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Only AGENT actuaries can be modified."));
    }

    @Test
    @DisplayName("PATCH /actuaries/{employeeId}/reset-limit - 200 OK resetuje usedLimit")
    void resetUsedLimitReturnsUpdatedDto() throws Exception {
        ActuaryInfoDto resetDto = new ActuaryInfoDto();
        resetDto.setId(1L);
        resetDto.setEmployeeId(10L);
        resetDto.setEmployeeName("Marko Markovic");
        resetDto.setActuaryType("AGENT");
        resetDto.setDailyLimit(new BigDecimal("100000.00"));
        resetDto.setUsedLimit(BigDecimal.ZERO);
        resetDto.setNeedApproval(false);

        when(actuaryService.resetUsedLimit(10L)).thenReturn(resetDto);

        mockMvc.perform(patch("/actuaries/10/reset-limit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedLimit").value(0))
                .andExpect(jsonPath("$.dailyLimit").value(100000.00));
    }

    @Test
    @DisplayName("PATCH /actuaries/{employeeId}/reset-limit - 404 kada zapis ne postoji")
    void resetUsedLimitNotFoundReturns404() throws Exception {
        when(actuaryService.resetUsedLimit(999L))
                .thenThrow(new IllegalArgumentException("Actuary info for employee with ID 999 not found."));

        mockMvc.perform(patch("/actuaries/999/reset-limit"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Actuary info for employee with ID 999 not found."));
    }

    @Test
    @DisplayName("PATCH /actuaries/{employeeId}/limit - 200 OK i prosledjuje dto service-u")
    void updateAgentLimit_returnsUpdatedDto() throws Exception {
        ActuaryInfoDto updated = new ActuaryInfoDto();
        updated.setId(2L);
        updated.setEmployeeId(11L);
        updated.setEmployeeName("Jelena Jovanovic");
        updated.setActuaryType("AGENT");
        updated.setDailyLimit(new BigDecimal("65000.00"));
        updated.setUsedLimit(BigDecimal.ZERO);
        updated.setNeedApproval(false);

        when(actuaryService.updateAgentLimit(eq(11L), any(UpdateActuaryLimitDto.class)))
                .thenReturn(updated);

        mockMvc.perform(patch("/actuaries/11/limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyLimit\":65000.00,\"needApproval\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value(11))
                .andExpect(jsonPath("$.dailyLimit").value(65000.00))
                .andExpect(jsonPath("$.needApproval").value(false));

        ArgumentCaptor<UpdateActuaryLimitDto> dtoCaptor = ArgumentCaptor.forClass(UpdateActuaryLimitDto.class);
        verify(actuaryService).updateAgentLimit(eq(11L), dtoCaptor.capture());
        assertThat(dtoCaptor.getValue().getDailyLimit()).isEqualByComparingTo("65000.00");
        assertThat(dtoCaptor.getValue().getNeedApproval()).isFalse();
    }

    @Test
    @DisplayName("PATCH /actuaries/{employeeId}/limit - 404 kada cilj ne postoji")
    void updateAgentLimit_notFound_returns404() throws Exception {
        when(actuaryService.updateAgentLimit(eq(999L), any(UpdateActuaryLimitDto.class)))
                .thenThrow(new IllegalArgumentException("User does not exist or isn't an actuary."));

        mockMvc.perform(patch("/actuaries/999/limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyLimit\":12345}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("User does not exist or isn't an actuary."));
    }

    @Test
    @DisplayName("PATCH /actuaries/{employeeId}/limit - 403 kada nije dozvoljeno")
    void updateAgentLimit_forbidden_returns403() throws Exception {
        when(actuaryService.updateAgentLimit(eq(11L), any(UpdateActuaryLimitDto.class)))
                .thenThrow(new IllegalStateException("Only supervisors can update agent limits."));

        mockMvc.perform(patch("/actuaries/11/limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"needApproval\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Only supervisors can update agent limits."));
    }
}
