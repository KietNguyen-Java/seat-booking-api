package com.kietnguyen.booking.seat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

@Entity
@Table(name = "seat")
public class Seat {

    @Id
    private Long id;

    @Column(name = "event_id", nullable = false)
    private long eventId;

    @Column(name = "row_label", nullable = false)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int number;

    @Column(name = "price_vnd", nullable = false)
    private long priceVnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status;

    @Column(name = "hold_id")
    private UUID holdId;

    /**
     * Optimistic-lock version. Seat changes already happen under a row lock; the version is a
     * second line of defence that makes any unlocked, conflicting update fail instead of
     * silently overwriting.
     */
    @Version
    private long version;

    protected Seat() {
        // for JPA
    }

    public void hold(UUID hold) {
        if (status != SeatStatus.AVAILABLE) {
            throw new IllegalStateException("seat " + label() + " is " + status);
        }
        status = SeatStatus.HELD;
        holdId = hold;
    }

    public String label() {
        return rowLabel + number;
    }

    public Long getId() {
        return id;
    }

    public long getEventId() {
        return eventId;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public int getNumber() {
        return number;
    }

    public long getPriceVnd() {
        return priceVnd;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public UUID getHoldId() {
        return holdId;
    }
}
