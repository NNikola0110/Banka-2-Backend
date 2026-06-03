package rs.raf.trading.investmentfund.controller.exception_handler;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import rs.raf.trading.investmentfund.exception.FundInactiveException;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.exception.UnsupportedCurrencyException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OT-1144 (TEST-tr-funds-dividends-profitbank-1): error-contract pin za
 * {@link InvestmentFundExceptionHandler}.
 *
 * <p>Kljucni karakterizacioni nalaz: {@code IllegalStateException} (npr.
 * "Only supervisors can create funds" iz {@code createFund}, ili "Banka kao
 * klijent nije seed-ovana" iz {@code resolveBankOwnerClientId}) mapira se na
 * <b>403 FORBIDDEN</b>, dok je neaktivan fond ({@link FundInactiveException})
 * <b>409 CONFLICT</b> (R1 485 — state-conflict, ne authz-denial). Ova razlika
 * je load-bearing: pre R1 485 su oba isla na 403. T8IntegrationTest pokriva samo
 * 400 (IllegalArgument); 403/409/404 mape nisu bile pinovane.</p>
 */
class InvestmentFundExceptionHandlerTest {

    private final InvestmentFundExceptionHandler handler = new InvestmentFundExceptionHandler();

    @Test
    @DisplayName("IllegalStateException -> 403 FORBIDDEN (npr. 'Only supervisors can create funds')")
    void illegalState_mapsTo403() {
        ResponseEntity<Map<String, String>> response =
                handler.handleIllegalState(new IllegalStateException("Only supervisors can create funds."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("message", "Only supervisors can create funds.");
    }

    @Test
    @DisplayName("FundInactiveException -> 409 CONFLICT (R1 485: state-conflict, NE 403)")
    void fundInactive_mapsTo409_notForbidden() {
        ResponseEntity<Map<String, String>> response =
                handler.handleFundInactive(new FundInactiveException("Fond X nije aktivan."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("message", "Fond X nije aktivan.");
    }

    @Test
    @DisplayName("EntityNotFoundException -> 404 NOT_FOUND")
    void entityNotFound_mapsTo404() {
        ResponseEntity<Map<String, String>> response =
                handler.handleNotFound(new EntityNotFoundException("Fund #5 not found."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("message", "Fund #5 not found.");
    }

    @Test
    @DisplayName("IllegalArgumentException -> 400 BAD_REQUEST")
    void illegalArgument_mapsTo400() {
        ResponseEntity<Map<String, String>> response =
                handler.handleIllegalArgument(new IllegalArgumentException("Iznos uplate mora biti pozitivan."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Iznos uplate mora biti pozitivan.");
    }

    @Test
    @DisplayName("InsufficientFundsException -> 400 BAD_REQUEST")
    void insufficientFunds_mapsTo400() {
        ResponseEntity<Map<String, String>> response =
                handler.handleInsufficientFunds(new InsufficientFundsException("Nedovoljno sredstava."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Nedovoljno sredstava.");
    }

    @Test
    @DisplayName("UnsupportedCurrencyException -> 400 BAD_REQUEST")
    void unsupportedCurrency_mapsTo400() {
        ResponseEntity<Map<String, String>> response =
                handler.handleUnsupportedCurrency(new UnsupportedCurrencyException("Nepodrzana valuta XXX."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Nepodrzana valuta XXX.");
    }

    @Test
    @DisplayName("AccessDeniedException -> 403 FORBIDDEN")
    void accessDenied_mapsTo403() {
        ResponseEntity<Map<String, String>> response =
                handler.handleAccessDenied(new AccessDeniedException("Nemate pravo."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("message", "Nemate pravo.");
    }
}
