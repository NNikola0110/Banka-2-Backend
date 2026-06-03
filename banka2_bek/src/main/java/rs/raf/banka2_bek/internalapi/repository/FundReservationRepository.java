package rs.raf.banka2_bek.internalapi.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.banka2_bek.internalapi.model.FundReservation;

import java.util.Optional;

public interface FundReservationRepository extends JpaRepository<FundReservation, Long> {

    // R1-708: ne-locking findByReservationId uklonjen (0 callera) — sve rezervacijske
    // mutacije idu kroz pesimisticki-zakljucani findByReservationIdForUpdate.

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from FundReservation r where r.reservationId = :rid")
    Optional<FundReservation> findByReservationIdForUpdate(@Param("rid") String rid);
}
