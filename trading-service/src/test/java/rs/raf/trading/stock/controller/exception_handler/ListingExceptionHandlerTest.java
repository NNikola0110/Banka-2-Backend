package rs.raf.trading.stock.controller.exception_handler;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import rs.raf.trading.common.dto.MessageResponseDto;

import static org.assertj.core.api.Assertions.assertThat;

class ListingExceptionHandlerTest {

    private ListingExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ListingExceptionHandler();
    }

    @Test
    void handleNotFound_returnsNotFound() {
        EntityNotFoundException ex = new EntityNotFoundException("Listing not found");

        ResponseEntity<MessageResponseDto> response = handler.handleNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Listing not found");
    }

    @Test
    void handleIllegalArgument_returnsBadRequest() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid ticker");

        ResponseEntity<MessageResponseDto> response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid ticker");
    }

    @Test
    void handleIllegalState_returnsForbidden() {
        IllegalStateException ex = new IllegalStateException("Market closed");

        ResponseEntity<MessageResponseDto> response = handler.handleIllegalState(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Market closed");
    }
}
