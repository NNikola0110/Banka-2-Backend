package rs.raf.banka2_bek.payment.model;

import jakarta.persistence.*;
import lombok.*;

import rs.raf.banka2_bek.client.model.Client;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment_recipients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Client who owns this list of saved payment recipients
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(nullable = false, length = 100)
    private String name;            // Recipient name or full name

    // Cross-bank: 18 (B2) do 34 cifre (IBAN max) — partner-banke kao Banka 1 imaju
    // 19-cifrene racune koji moraju moci da se sacuvaju kao sablon primaoca.
    @Column(nullable = false, length = 34)
    private String accountNumber;   // Recipient account number

    @Column(nullable = false, updatable = false)
    @org.hibernate.annotations.ColumnDefault("CURRENT_TIMESTAMP")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
