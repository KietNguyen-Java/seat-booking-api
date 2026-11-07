package com.kietnguyen.booking.seat;

/** A seat as plain values: a query projection, not an entity. */
public record SeatView(long id, String rowLabel, int number, long priceVnd, SeatStatus status) {

    public String label() {
        return rowLabel + number;
    }

    public boolean available() {
        return status == SeatStatus.AVAILABLE;
    }
}
