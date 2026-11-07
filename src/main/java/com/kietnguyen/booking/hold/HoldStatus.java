package com.kietnguyen.booking.hold;

/**
 * {@code HELD} is the only state that can change: to {@code CONFIRMED} by payment, to
 * {@code RELEASED} by the customer, or to {@code EXPIRED} by the expiry job.
 */
public enum HoldStatus { HELD, CONFIRMED, RELEASED, EXPIRED }
