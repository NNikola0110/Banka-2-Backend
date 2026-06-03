package rs.raf.banka2_bek.card.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import rs.raf.banka2_bek.card.model.CardCategory;
import rs.raf.banka2_bek.card.model.CardType;

import java.math.BigDecimal;

@Data
public class CreateCardRequestDto {
    @NotNull(message = "Account ID je obavezan")
    private Long accountId;
    // R1-635: bez @PositiveOrZero negativan cardLimit/creditLimit je prolazio kroz
    // validaciju i zavrsavao u bazi (kartica sa limitom < 0). Cap na >= 0 daje
    // cistu 400 gresku umesto da nekonzistentno stanje udje u domen.
    @PositiveOrZero(message = "Limit kartice ne sme biti negativan")
    private BigDecimal cardLimit;
    private CardType cardType;
    /** Kategorija: DEBIT (default) / CREDIT / INTERNET_PREPAID. */
    private CardCategory cardCategory;
    /** Za CREDIT: maksimalni kredit limit (banka odobrava). Ignorise se za ostale. */
    @PositiveOrZero(message = "Kreditni limit ne sme biti negativan")
    private BigDecimal creditLimit;
}
