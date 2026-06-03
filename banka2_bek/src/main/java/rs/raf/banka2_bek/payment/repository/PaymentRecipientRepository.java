package rs.raf.banka2_bek.payment.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.payment.model.PaymentRecipient;

import java.util.List;
import java.util.Optional;

public interface PaymentRecipientRepository extends JpaRepository<PaymentRecipient, Long> {

    List<PaymentRecipient> findByClientOrderByCreatedAtDesc(Client client);

    Page<PaymentRecipient> findByClientOrderByCreatedAtDesc(Client client, Pageable pageable);

    Optional<PaymentRecipient> findByIdAndClient(Long id, Client client);

    boolean existsByIdAndClient(Long id, Client client);

    // R1-647: dedup primaoca — isti klijent ne treba da ima N identicnih unosa
    // (isti broj racuna). Aplikativni guard (bez DB UNIQUE indeksa da ne diramo schema).
    boolean existsByClientAndAccountNumber(Client client, String accountNumber);
}
