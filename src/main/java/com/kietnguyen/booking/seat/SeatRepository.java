package com.kietnguyen.booking.seat;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /** The seat map as plain values. Reading it this way puts no entities in the persistence context. */
    @Query("""
            select new com.kietnguyen.booking.seat.SeatView(s.id, s.rowLabel, s.number, s.priceVnd, s.status)
            from Seat s where s.eventId = :eventId
            order by s.rowLabel, s.number
            """)
    List<SeatView> seatMap(@Param("eventId") long eventId);

    /**
     * {@code SELECT ... FOR UPDATE}: locks the rows until the transaction ends. A second request
     * for any of these seats waits here, then sees the first request's committed result.
     * Rows are locked in id order, so two requests for overlapping seats always lock in the same
     * order and cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.eventId = :eventId and s.id in :ids order by s.id")
    List<Seat> lockSeats(@Param("eventId") long eventId, @Param("ids") Collection<Long> ids);

    List<Seat> findByHoldIdOrderByRowLabelAscNumberAsc(UUID holdId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Seat s set s.status = com.kietnguyen.booking.seat.SeatStatus.SOLD, s.version = s.version + 1
            where s.holdId = :holdId and s.status = com.kietnguyen.booking.seat.SeatStatus.HELD
            """)
    int markSold(@Param("holdId") UUID holdId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Seat s set s.status = com.kietnguyen.booking.seat.SeatStatus.AVAILABLE, s.holdId = null,
                              s.version = s.version + 1
            where s.holdId = :holdId and s.status = com.kietnguyen.booking.seat.SeatStatus.HELD
            """)
    int release(@Param("holdId") UUID holdId);
}
