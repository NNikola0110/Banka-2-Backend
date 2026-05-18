package rs.raf.trading.stock.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.raf.trading.stock.controller.exception_handler.ListingExceptionHandler;
import rs.raf.trading.stock.dto.ListingDailyPriceDto;
import rs.raf.trading.stock.dto.ListingDto;
import rs.raf.trading.stock.service.ListingService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ListingControllerTest {

    private MockMvc mockMvc;
    private ListingService listingService;

    @BeforeEach
    void setUp() {
        listingService = mock(ListingService.class);
        ListingController controller = new ListingController(listingService);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new ListingExceptionHandler())
                .build();
    }

    // ─── GET /listings ────────────────────────────────────────────────────────────

    @Test
    void getListings_defaultParams_returns200WithPage() throws Exception {
        ListingDto dto = new ListingDto();
        dto.setId(1L);
        dto.setTicker("AAPL");
        dto.setListingType("STOCK");

        Page<ListingDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
        when(listingService.getListings(
                anyString(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(),
                anyInt(), anyInt()))
                .thenReturn(page);

        mockMvc.perform(get("/listings")
                        .param("type", "STOCK")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$.content[0].listingType").value("STOCK"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getListings_withSearchParam_passesSearchToService() throws Exception {
        Page<ListingDto> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(listingService.getListings(
                anyString(), anyString(), isNull(),
                isNull(), isNull(), isNull(), isNull(),
                anyInt(), anyInt()))
                .thenReturn(emptyPage);

        mockMvc.perform(get("/listings")
                        .param("type", "STOCK")
                        .param("search", "AAPL"))
                .andExpect(status().isOk());

        verify(listingService).getListings(
                eq("STOCK"), eq("AAPL"), isNull(),
                isNull(), isNull(), isNull(), isNull(),
                eq(0), eq(20));
    }

    @Test
    void getListings_withPriceFilters_passesFiltersToService() throws Exception {
        Page<ListingDto> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(listingService.getListings(
                anyString(), isNull(), isNull(),
                any(BigDecimal.class), any(BigDecimal.class), isNull(), isNull(),
                anyInt(), anyInt()))
                .thenReturn(emptyPage);

        mockMvc.perform(get("/listings")
                        .param("type", "STOCK")
                        .param("priceMin", "100")
                        .param("priceMax", "500"))
                .andExpect(status().isOk());

        verify(listingService).getListings(
                eq("STOCK"), isNull(), isNull(),
                any(BigDecimal.class), any(BigDecimal.class), isNull(), isNull(),
                eq(0), eq(20));
    }

    @Test
    void getListings_illegalArgumentException_returns400() throws Exception {
        when(listingService.getListings(
                anyString(), any(), any(),
                any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenThrow(new IllegalArgumentException("Unknown listing type: OPTION"));

        mockMvc.perform(get("/listings")
                        .param("type", "OPTION"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown listing type: OPTION"));
    }

    @Test
    void getListings_illegalStateException_returns403() throws Exception {
        when(listingService.getListings(
                anyString(), any(), any(),
                any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("Klijenti nemaju pristup FOREX hartijama."));

        mockMvc.perform(get("/listings")
                        .param("type", "FOREX"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Klijenti nemaju pristup FOREX hartijama."));
    }

    // ─── GET /listings/{id} ───────────────────────────────────────────────────────

    @Test
    void getListingById_existingId_returns200WithDto() throws Exception {
        ListingDto dto = new ListingDto();
        dto.setId(42L);
        dto.setTicker("MSFT");
        dto.setName("Microsoft Corporation");
        dto.setListingType("STOCK");
        dto.setPrice(BigDecimal.valueOf(300));

        when(listingService.getListingById(42L)).thenReturn(dto);

        mockMvc.perform(get("/listings/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.ticker").value("MSFT"))
                .andExpect(jsonPath("$.name").value("Microsoft Corporation"))
                .andExpect(jsonPath("$.listingType").value("STOCK"))
                .andExpect(jsonPath("$.price").value(300));
    }

    @Test
    void getListingById_notFound_returns404() throws Exception {
        when(listingService.getListingById(999L))
                .thenThrow(new EntityNotFoundException("Listing not found with id: 999"));

        mockMvc.perform(get("/listings/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Listing not found with id: 999"));
    }

    @Test
    void getListingById_illegalStateException_returns403() throws Exception {
        when(listingService.getListingById(5L))
                .thenThrow(new IllegalStateException("Klijenti nemaju pristup FOREX hartijama."));

        mockMvc.perform(get("/listings/5"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Klijenti nemaju pristup FOREX hartijama."));
    }

    // ─── GET /listings/{id}/history ───────────────────────────────────────────────

    @Test
    void getListingHistory_validPeriod_returns200WithList() throws Exception {
        ListingDailyPriceDto priceDto = new ListingDailyPriceDto();
        priceDto.setDate(LocalDate.of(2026, 5, 1));
        priceDto.setPrice(BigDecimal.valueOf(145));
        priceDto.setHigh(BigDecimal.valueOf(148));
        priceDto.setLow(BigDecimal.valueOf(143));
        priceDto.setChange(BigDecimal.valueOf(2));
        priceDto.setVolume(1000000L);

        when(listingService.getListingHistory(1L, "MONTH")).thenReturn(List.of(priceDto));

        mockMvc.perform(get("/listings/1/history")
                        .param("period", "MONTH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].price").value(145))
                .andExpect(jsonPath("$[0].high").value(148))
                .andExpect(jsonPath("$[0].volume").value(1000000));
    }

    @Test
    void getListingHistory_defaultPeriod_usesMONTH() throws Exception {
        when(listingService.getListingHistory(1L, "MONTH")).thenReturn(List.of());

        mockMvc.perform(get("/listings/1/history"))
                .andExpect(status().isOk());

        verify(listingService).getListingHistory(1L, "MONTH");
    }

    @Test
    void getListingHistory_invalidPeriod_returns400() throws Exception {
        when(listingService.getListingHistory(1L, "QUARTER"))
                .thenThrow(new IllegalArgumentException("Period moze biti: DAY, WEEK, MONTH, YEAR, FIVE_YEARS, ALL"));

        mockMvc.perform(get("/listings/1/history")
                        .param("period", "QUARTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Period moze biti: DAY, WEEK, MONTH, YEAR, FIVE_YEARS, ALL"));
    }

    @Test
    void getListingHistory_notFound_returns404() throws Exception {
        when(listingService.getListingHistory(999L, "MONTH"))
                .thenThrow(new EntityNotFoundException("Listing not found with id: 999"));

        mockMvc.perform(get("/listings/999/history"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Listing not found with id: 999"));
    }

    // ─── POST /listings/refresh ───────────────────────────────────────────────────

    @Test
    void refreshPrices_returns200() throws Exception {
        mockMvc.perform(post("/listings/refresh"))
                .andExpect(status().isOk());

        verify(listingService).refreshPrices();
    }
}
