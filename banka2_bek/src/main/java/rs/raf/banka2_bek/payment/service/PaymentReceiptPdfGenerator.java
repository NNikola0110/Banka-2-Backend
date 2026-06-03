package rs.raf.banka2_bek.payment.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import rs.raf.banka2_bek.transaction.dto.TransactionResponseDto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Generise PDF potvrdu transakcije.
 *
 * <p>P0-B4 Nalaz 2: koristi embedovan Unicode TTF (Open Sans, {@link PDType0Font})
 * umesto Standard-14 WinAnsi fonta. WinAnsi {@code showText} baca
 * {@link IllegalArgumentException} na karaktere van Latin-1 (srpski č/ć/š/ž/đ),
 * pa je "Stampaj potvrdu" pucao sa 500. Open Sans pokriva Latin Extended-A pa
 * sve srpske latinicke dijakritike renderuju ispravno.</p>
 *
 * <p>P1-i18n-1 / 1855: tekst potvrde je na srpskom (svi mejlovi i ostatak
 * proizvoda su srpski sa dijakritikom — engleski PDF je kvario kohezivnost).
 * Open Sans pokriva srpske dijakritike pa labele renderuju ispravno. Masinske
 * vrednosti (tip transakcije, brojevi, valuta) ostaju nepromenjene.</p>
 */
@Service
@Slf4j
public class PaymentReceiptPdfGenerator {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String TITLE_FONT_PATH = "fonts/OpenSans-Bold.ttf";
    private static final String BODY_FONT_PATH = "fonts/OpenSans-Regular.ttf";

    public byte[] generate(TransactionResponseDto transaction) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            // PDType0Font je vezan za PDDocument — ucitavamo ga per-dokument.
            PDType0Font titleFont = loadFont(document, TITLE_FONT_PATH);
            PDType0Font bodyFont = loadFont(document, BODY_FONT_PATH);

            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                List<String> lines = buildLines(transaction);

                contentStream.beginText();
                contentStream.setFont(titleFont, 14);
                contentStream.newLineAtOffset(50, 780);
                contentStream.showText(sanitize("Potvrda o transakciji", titleFont));
                contentStream.newLineAtOffset(0, -24);

                contentStream.setFont(bodyFont, 11);
                for (String line : lines) {
                    contentStream.showText(sanitize(line, bodyFont));
                    contentStream.newLineAtOffset(0, -16);
                }
                contentStream.endText();
            }

            document.save(outputStream);
            return outputStream.toByteArray();
        } catch (IOException ex) {
            log.error("Failed to generate PDF receipt for transaction {}", transaction.getId(), ex);
            throw new IllegalStateException("Failed to generate transaction receipt PDF.");
        }
    }

    /**
     * Ucitava embedovan Unicode TTF iz classpath-a i embeduje ga u dokument.
     * {@code embedSubset=true} ubacuje samo koriscene glyph-ove (manji PDF).
     */
    private PDType0Font loadFont(PDDocument document, String resourcePath) throws IOException {
        try (InputStream fontStream = new ClassPathResource(resourcePath).getInputStream()) {
            return PDType0Font.load(document, fontStream, true);
        }
    }

    /**
     * Robusno ciscenje teksta pre {@code showText}: izbacuje kontrolne karaktere
     * (newline/tab koje PDFBox odbija) i sve codepoint-ove koje embedovan font ne
     * pokriva, zamenjujuci ih sa '?' da generisanje NIKAD ne pukne na egzoticnom
     * ulazu (npr. emoji ili cirilica van fonta). Open Sans pokriva ceo latinicki
     * set + srpske dijakritike pa se za normalan unos nista ne menja.
     */
    private String sanitize(String text, PDType0Font font) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                sb.append(' ');
                return;
            }
            if (Character.isISOControl(cp)) {
                return; // preskoci ostale kontrolne karaktere
            }
            if (fontCanRender(font, cp)) {
                sb.appendCodePoint(cp);
            } else {
                sb.append('?');
            }
        });
        return sb.toString();
    }

    private boolean fontCanRender(PDType0Font font, int codePoint) {
        try {
            return font.hasGlyph(codePoint);
        } catch (IOException e) {
            return false;
        }
    }

    private List<String> buildLines(TransactionResponseDto transaction) {
        List<String> lines = new ArrayList<>();
        lines.add("Broj transakcije: " + transaction.getId());
        lines.add("Datum: " + (transaction.getCreatedAt() == null ? "-" : transaction.getCreatedAt().format(DATE_TIME_FORMATTER)));
        // Tip transakcije je masinski enum (PAYMENT/TRANSFER...) — ostaje nepromenjen.
        lines.add("Tip: " + (transaction.getType() == null ? "-" : transaction.getType().name()));
        lines.add("Smer: " + resolveDirection(transaction));
        lines.add("Sa računa: " + safe(transaction.getAccountNumber()));
        lines.add("Na račun: " + safe(transaction.getToAccountNumber()));
        lines.add("Iznos: " + resolveAmount(transaction));
        lines.add("Valuta: " + safe(transaction.getCurrencyCode()));

        lines.add("Opis: " + safe(transaction.getDescription()));
        return lines;
    }

    private String resolveDirection(TransactionResponseDto transaction) {
        return positive(transaction.getDebit()) ? "Odlazna" : "Dolazna";
    }

    private String resolveAmount(TransactionResponseDto transaction) {
        BigDecimal amount = positive(transaction.getDebit()) ? transaction.getDebit() : transaction.getCredit();
        return safeDecimal(amount);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String safeDecimal(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
