package com.kietnguyen.booking.hold;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "seat_hold")
public class SeatHold {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private long eventId;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status;

    @Column(name = "seat_count", nullable = false)
    private int seatCount;

    @Column(name = "total_price_vnd", nullable = false)
    private long totalPriceVnd;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    /** SHA-256 of the request body, to detect a key reused for a different request. */
    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    protected SeatHold() {
        // for JPA
    }

    public SeatHold(long eventId, String customerEmail, int seatCount, long totalPriceVnd,
                    String idempotencyKey, String requestHash, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.customerEmail = customerEmail;
        this.status = HoldStatus.HELD;
        this.seatCount = seatCount;
        this.totalPriceVnd = totalPriceVnd;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    void confirm(Instant now) {
        status = HoldStatus.CONFIRMED;
        confirmedAt = now;
    }

    void release() {
        status = HoldStatus.RELEASED;
    }

    void expire() {
        status = HoldStatus.EXPIRED;
    }

    public UUID getId() {
        return id;
    }

    public long getEventId() {
        return eventId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public int getSeatCount() {
        return seatCount;
    }

    public long getTotalPriceVnd() {
        return totalPriceVnd;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
