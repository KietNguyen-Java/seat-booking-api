package com.kietnguyen.booking.hold;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatHoldRepository extends JpaRepository<SeatHold, UUID> {

    Optional<SeatHold> findByIdempotencyKey(String idempotencyKey);

    /** Locks the hold so confirm, release and expiry of the same hold run one after another. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from SeatHold h where h.id = :id")
    Optional<SeatHold> lockById(@Param("id") UUID id);

    /**
     * Expired holds, locked with {@code FOR UPDATE SKIP LOCKED} (lock timeout -2 in Hibernate):
     * a hold that a customer is confirming right now is skipped instead of waited for, and two
     * instances of the expiry job never process the same hold.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select h from SeatHold h
            where h.status = com.kietnguyen.booking.hold.HoldStatus.HELD and h.expiresAt <= :now
            order by h.expiresAt
            """)
    List<SeatHold> lockExpired(@Param("now") Instant now, Limit limit);
}
