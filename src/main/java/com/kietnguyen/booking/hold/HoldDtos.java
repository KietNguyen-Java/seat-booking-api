package com.kietnguyen.booking.hold;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class HoldDtos {

    public static final int MAX_SEATS_PER_HOLD = 8;

    private HoldDtos() {
    }

    /**
     * Either {@code seatIds} (the customer picked seats on the map) or {@code quantity} (best
     * adjacent seats chosen by the server), not both.
     */
    public record CreateHoldRequest(
            @NotBlank @Email @Size(max = 254) String customerEmail,
            @Size(max = MAX_SEATS_PER_HOLD) List<@NotNull Long> seatIds,
            @Min(1) @Max(MAX_SEATS_PER_HOLD) Integer quantity) {
    }

    public record HoldResponse(UUID id, long eventId, HoldStatus status, List<String> seats, long totalPriceVnd,
                               Instant expiresAt, Instant confirmedAt) {
    }
}
