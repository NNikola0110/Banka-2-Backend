package rs.raf.banka2_bek.payment.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.raf.banka2_bek.transaction.dto.TransactionResponseDto;
import rs.raf.banka2_bek.transaction.dto.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-B4 Nalaz 2: PDF potvrda mora preziveti srpske dijakritike (c/c/s/z/dj).
 *
 * <p>Standard14 WinAnsi {@code showText} baca {@code IllegalArgumentException}
 * (van IOException catch-a) na karaktere van Latin-1 (npr. č ć š ž đ), pa
 * "Stampaj potvrdu" puca sa 500. Fix: embedovan Unicode TTF (PDType0Font).</p>
 */
class PaymentReceiptPdfDiacriticsTest {

    private PaymentReceiptPdfGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new PaymentReceiptPdfGenerator();
    }

    private String extractPdfText(byte[] pdfBytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void generate_serbianDiacritics_doesNotThrow() {
        TransactionResponseDto dto = TransactionResponseDto.builder()
                .id(1001L)
                .accountNumber("265-0000000012345-78")
                .toAccountNumber("265-0000000098765-43")
                .currencyCode("RSD")
                .description("Račun za struju č/ć/š/ž/đ — Đorđe Žižić")
                .type(TransactionType.PAYMENT)
                .debit(new BigDecimal("4567.89"))
                .credit(BigDecimal.ZERO)
                .createdAt(LocalDateTime.of(2026, 5, 31, 10, 15, 0))
                .build();

        byte[] pdf = assertDoesNotThrow(() -> generator.generate(dto));
        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
    }

    @Test
    void generate_serbianDiacritics_textIsExtractable() throws Exception {
        TransactionResponseDto dto = TransactionResponseDto.builder()
                .id(1002L)
                .accountNumber("265-1111111111111-11")
                .toAccountNumber("265-2222222222222-22")
                .currencyCode("EUR")
                .description("Plaćanje članarine — Šabac, Čačak, Đakovica")
                .type(TransactionType.PAYMENT)
                .debit(new BigDecimal("250.00"))
                .credit(BigDecimal.ZERO)
                .createdAt(LocalDateTime.of(2026, 5, 31, 11, 0, 0))
                .build();

        byte[] pdf = generator.generate(dto);
        String text = extractPdfText(pdf);

        // Embedovan Unicode font mora sacuvati dijakritike u izvuceni tekst.
        assertTrue(text.contains("Plaćanje članarine"),
                "Ocekivan tekst sa dijakriticima, dobijeno: " + text);
        assertTrue(text.contains("Šabac"));
        assertTrue(text.contains("Đakovica"));
    }
}
