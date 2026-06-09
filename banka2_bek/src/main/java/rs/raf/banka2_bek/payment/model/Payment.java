package rs.raf.banka2_bek.payment.model;

import jakarta.persistence.*;
import lombok.*;

import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.currency.model.Currency;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payments_order_number", columnNames = "order_number")
})@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String orderNumber;         // Automatski generisan broj naloga

    // INBOUND inter-bank uplata: posiljaocev racun je u DRUGOJ banci, pa lokalno
    // ne postoji Account za njega → fromAccount je NULLABLE. Za sva ostala placanja
    // (lokalna + outbound inter-bank) fromAccount je i dalje obavezan (validira se u
    // servisu). resolveDirection/isPartyToPayment vec tretiraju null fromAccount kao
    // INCOMING uplatu cija se vidljivost vodi preko toAccountNumber vlasnistva.
    // NAPOMENA: ddl-auto=update NE skida postojeci NOT NULL na vec-deployovanoj bazi —
    // na zivoj bazi treba rucni `ALTER TABLE payments ALTER COLUMN from_account_id DROP NOT NULL`.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;        // Račun platioca (null za inbound inter-bank uplatu)

    // Interbank: partnerske banke mogu imati racune razlicite duzine (Banka 1 koristi
    // 19 cifara, ne 18). Cap podignut na 34 (IBAN max) da inter-bank placanje ka
    // partneru sa duzim brojem racuna ne padne na DataIntegrityViolation. Vidi i
    // CreatePaymentRequestDto.toAccount @Length. NAPOMENA: ddl-auto=update NE siri
    // postojecu kolonu — na vec-deployovanoj bazi treba rucni ALTER (vidi INSTRUKCIJE).
    @Column(nullable = false, length = 34)
    private String toAccountNumber;     // Broj računa primaoca (string — može biti u drugoj banci)

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @org.hibernate.annotations.ColumnDefault("0")
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal fee = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "currency_id", nullable = false)
    private Currency currency;

    @Column(length = 200)
    private String recipientName;       // Ime i prezime / naziv primaoca

    @Column(length = 10)
    private String paymentCode;         // Šifra plaćanja (iz šifarnika)

    @Column(length = 50)
    private String referenceNumber;     // Poziv na broj

    @Column(length = 200)
    private String purpose;             // Svrha plaćanja

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @org.hibernate.annotations.ColumnDefault("'PROCESSING'")
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PROCESSING;

    @Column(name = "interbank_tx_id_string", length = 64)
    private String interbankTxIdString;

    @Column(name = "interbank_tx_routing_number")
    private Integer interbankTxRoutingNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private Client createdBy;

    //trenutno UTC zbog docker env-a ili tako nesto
    @Column(nullable = false, updatable = false)
    @org.hibernate.annotations.ColumnDefault("CURRENT_TIMESTAMP")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
