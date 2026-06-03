package rs.raf.banka2_bek.internalapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.banka2_bek.internalapi.service.InternalLookupService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEST-branches-7: path-routing kolizija izmedju literal-segment rute
 * {@code GET /internal/users/by-email/{email}} i parametarske
 * {@code GET /internal/users/{userRole}/{id}}.
 *
 * <p>Verifikuje da Spring bira KONKRETNIJU (literal {@code by-email}) rutu za
 * {@code /internal/users/by-email/...} i da {@code {userRole}/{id}} ruta NE
 * "proguta" by-email zahteve (inace bi pokusala da parsira "by-email" kao
 * {@code userRole} i email kao {@code Long id} → 400/pogresan handler).
 */
@ExtendWith(MockitoExtension.class)
class InternalUsersControllerTest {

    @Mock
    private InternalLookupService lookupService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InternalUsersController controller = new InternalUsersController(lookupService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private InternalUserDto dto() {
        return new InternalUserDto(7L, "CLIENT", "stefan@test.com",
                "Stefan", "Jovanovic", true, null);
    }

    @Test
    @DisplayName("GET /internal/users/by-email/{email} routes to getUserByEmail, NOT getUserById")
    void byEmailRoute_resolvesToEmailLookup_notIdLookup() throws Exception {
        when(lookupService.getUserByEmail("stefan@test.com")).thenReturn(dto());

        mockMvc.perform(get("/internal/users/by-email/{email}", "stefan@test.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.email").value("stefan@test.com"));

        verify(lookupService).getUserByEmail("stefan@test.com");
        // Parametarska {userRole}/{id} ruta se NE sme okinuti za by-email zahtev.
        verify(lookupService, never()).getUserById(anyString(), anyLong());
    }

    @Test
    @DisplayName("GET /internal/users/{userRole}/{id} routes to getUserById with numeric id")
    void idRoute_resolvesToIdLookup() throws Exception {
        when(lookupService.getUserById("CLIENT", 7L)).thenReturn(dto());

        mockMvc.perform(get("/internal/users/{role}/{id}", "CLIENT", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(7));

        verify(lookupService).getUserById("CLIENT", 7L);
        verify(lookupService, never()).getUserByEmail(anyString());
    }

    @Test
    @DisplayName("GET /internal/users/{userRole}/{id} with non-numeric id → 400 (not routed to by-email)")
    void idRoute_nonNumericId_returns400() throws Exception {
        // "abc" nije Long → type-mismatch → 400. Ne sme da padne u by-email handler.
        mockMvc.perform(get("/internal/users/{role}/{id}", "CLIENT", "abc"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(lookupService);
    }

    @Test
    @DisplayName("OT-1061: GET /internal/users/supervisors returns supervisor IDs, NOT routed to {userRole}/{id}")
    void supervisorsRoute_returnsIds_notRoutedToIdLookup() throws Exception {
        when(lookupService.getSupervisorIds()).thenReturn(List.of(3L, 7L));

        mockMvc.perform(get("/internal/users/supervisors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(3))
                .andExpect(jsonPath("$[1]").value(7));

        verify(lookupService).getSupervisorIds();
        // Literal "supervisors" je jedan segment → NE sme da padne u {userRole}/{id} (dva segmenta)
        // niti u by-email handler.
        verify(lookupService, never()).getUserById(anyString(), anyLong());
        verify(lookupService, never()).getUserByEmail(anyString());
    }

    @Test
    @DisplayName("GET /internal/employees forwards optional filters to findEmployees")
    void employees_forwardsFilters() throws Exception {
        when(lookupService.findEmployees(eq("Tamara"), any(), any(), any()))
                .thenReturn(List.of(dto()));

        mockMvc.perform(get("/internal/employees").param("firstName", "Tamara"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(7));

        verify(lookupService).findEmployees(eq("Tamara"), any(), any(), any());
    }
}
