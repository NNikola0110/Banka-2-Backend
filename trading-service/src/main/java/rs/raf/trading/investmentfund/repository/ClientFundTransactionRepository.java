package rs.raf.trading.investmentfund.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rs.raf.trading.investmentfund.model.ClientFundTransaction;
import rs.raf.trading.investmentfund.model.ClientFundTransactionStatus;

import java.util.List;

public interface ClientFundTransactionRepository extends JpaRepository<ClientFundTransaction, Long> {

    List<ClientFundTransaction> findByFundIdOrderByCreatedAtDesc(Long fundId);

    List<ClientFundTransaction> findByStatus(ClientFundTransactionStatus status);

    List<ClientFundTransaction> findByFundIdAndStatusOrderByCreatedAtAsc(
            Long fundId,
            ClientFundTransactionStatus status
    );
}