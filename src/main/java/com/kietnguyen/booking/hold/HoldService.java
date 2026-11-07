package com.kietnguyen.booking.hold;

import com.kietnguyen.booking.BookingProperties;
import com.kietnguyen.booking.event.EventRepository;
import com.kietnguyen.booking.hold.HoldDtos.CreateHoldRequest;
import com.kietnguyen.booking.hold.HoldDtos.HoldResponse;
import com.kietnguyen.booking.seat.BestSeats;
import com.kietnguyen.booking.seat.Seat;
import com.kietnguyen.booking.seat.SeatRepository;
import com.kietnguyen.booking.seat.SeatStatus;
import com.kietnguyen.booking.seat.SeatView;
import com.kietnguyen.booking.web.ApiException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Holds seats for a short time so a customer can pay, and guarantees a seat is never held or
 * sold twice.
 *
 * <p>The guarantee comes from the database, not from Java locks, so it also holds with several
 * application instances: seats are read with {@code SELECT ... FOR UPDATE} inside the
 * transaction that changes them, and a CHECK constraint ties a seat's status to its hold.
 */
@Service
public class HoldService {

    /** How many times to re-read the seat map when the chosen seats are taken while we lock them. */
    private static final int BEST_SEAT_ATTEMPTS = 3;

    private final EventRepository events;
    private final SeatRepository seats;
    private final SeatHoldRepository holds;
    private final Clock clock;
    private final BookingProperties properties;

    public HoldService(EventRepository events, SeatRepository seats, SeatHoldRepository holds, Clock clock,
                       BookingProperties properties) {
        this.events = events;
        this.seats = seats;
        this.holds = holds;
        this.clock = clock;
        this.properties = properties;
    }

    /** {@code replayed} is true when the same request was already processed under this key. */
    public record Outcome(HoldResponse hold, boolean replayed) {
    }

    @Transactional
    public Outcome create(long eventId, String idempotencyKey, CreateHoldRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80) {
            throw ApiException.badRequest("Idempotency-Key header must be 1 to 80 characters");
        }
        boolean bySeatIds = request.seatIds() != null && !request.seatIds().isEmpty();
        if (bySeatIds == (request.quantity() != null)) {
            throw ApiException.badRequest("Give either seatIds or quantity");
        }

        // A client that timed out and retries with the same key gets the original answer back.
        String hash = requestHash(eventId, request);
        Optional<SeatHold> existing = holds.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "This Idempotency-Key was already used for a different request");
            }
            return new Outcome(response(existing.get()), true);
        }
        if (!events.existsById(eventId)) {
            throw ApiException.notFound("No event with id " + eventId);
        }

        List<Seat> locked = bySeatIds
                ? lockAvailable(eventId, new TreeSet<>(request.seatIds()))
                : lockBestBlock(eventId, request.quantity());

        Instant now = clock.instant();
        long total = locked.stream().mapToLong(Seat::getPriceVnd).sum();
        SeatHold hold = holds.saveAndFlush(new SeatHold(eventId, request.customerEmail().trim().toLowerCase(),
                locked.size(), total, idempotencyKey, hash, now, now.plus(properties.holdDuration())));
        locked.forEach(seat -> seat.hold(hold.getId()));
        return new Outcome(response(hold, locked), false);
    }

    @Transactional
    public HoldResponse confirm(UUID holdId) {
        SeatHold hold = lock(holdId);
        switch (hold.getStatus()) {
            case CONFIRMED -> {
                return response(hold); // confirming twice is harmless
            }
            case RELEASED -> throw ApiException.conflict("This hold was released");
            case EXPIRED -> throw new ApiException(HttpStatus.GONE, "This hold has expired");
            case HELD -> {
                if (hold.isExpiredAt(clock.instant())) {
                    throw new ApiException(HttpStatus.GONE, "This hold has expired");
                }
            }
        }
        hold.confirm(clock.instant());
        int sold = seats.markSold(holdId);
        if (sold != hold.getSeatCount()) {
            throw new IllegalStateException("hold " + holdId + " has " + hold.getSeatCount() + " seats but " + sold + " were sold");
        }
        return response(hold);
    }

    @Transactional
    public HoldResponse release(UUID holdId) {
        SeatHold hold = lock(holdId);
        if (hold.getStatus() == HoldStatus.CONFIRMED) {
            throw ApiException.conflict("This hold is already paid and cannot be released");
        }
        if (hold.getStatus() == HoldStatus.HELD) {
            hold.release();
            seats.release(holdId);
        }
        return response(hold);
    }

    @Transactional(readOnly = true)
    public HoldResponse get(UUID holdId) {
        return response(holds.findById(holdId).orElseThrow(() -> ApiException.notFound("No hold with id " + holdId)));
    }

    /** Expires up to 100 overdue holds and frees their seats. Returns how many were expired. */
    @Transactional
    public int expireDue() {
        List<SeatHold> due = holds.lockExpired(clock.instant(), Limit.of(100));
        due.forEach(SeatHold::expire); // change every hold before the bulk updates clear the session
        due.forEach(h -> seats.release(h.getId()));
        return due.size();
    }

    // ---------------------------------------------------------------- seat locking

    private List<Seat> lockAvailable(long eventId, Collection<Long> seatIds) {
        List<Seat> locked = seats.lockSeats(eventId, seatIds);
        if (locked.size() != seatIds.size()) {
            throw ApiException.notFound("Some seats do not exist in event " + eventId);
        }
        List<String> taken = locked.stream().filter(s -> s.getStatus() != SeatStatus.AVAILABLE).map(Seat::label).toList();
        if (!taken.isEmpty()) {
            throw ApiException.conflict("Seats already taken: " + String.join(", ", taken));
        }
        return locked;
    }

    /**
     * Reads the seat map without locks, picks the best block, then locks just those seats. If
     * another request took one of them in between, the lock shows it and we pick again.
     * This keeps the lock short and narrow instead of locking the whole event.
     */
    private List<Seat> lockBestBlock(long eventId, int quantity) {
        for (int attempt = 0; attempt < BEST_SEAT_ATTEMPTS; attempt++) {
            List<SeatView> block = BestSeats.choose(seats.seatMap(eventId), quantity)
                    .orElseThrow(() -> ApiException.conflict("No block of " + quantity + " adjacent seats is available"));
            List<Seat> locked = seats.lockSeats(eventId, block.stream().map(SeatView::id).toList());
            if (locked.stream().allMatch(s -> s.getStatus() == SeatStatus.AVAILABLE)) {
                return locked;
            }
        }
        throw ApiException.conflict("Seats are selling fast; please try again");
    }

    private SeatHold lock(UUID holdId) {
        return holds.lockById(holdId).orElseThrow(() -> ApiException.notFound("No hold with id " + holdId));
    }

    // ---------------------------------------------------------------- responses

    private HoldResponse response(SeatHold hold) {
        return response(hold, seats.findByHoldIdOrderByRowLabelAscNumberAsc(hold.getId()));
    }

    private static HoldResponse response(SeatHold hold, List<Seat> heldSeats) {
        return new HoldResponse(hold.getId(), hold.getEventId(), hold.getStatus(),
                heldSeats.stream().map(Seat::label).toList(), hold.getTotalPriceVnd(), hold.getExpiresAt(),
                hold.getConfirmedAt());
    }

    private static String requestHash(long eventId, CreateHoldRequest request) {
        String canonical = eventId + "|" + request.customerEmail().trim().toLowerCase() + "|"
                + (request.seatIds() == null ? "" : new TreeSet<>(request.seatIds())) + "|" + request.quantity();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JVM ships SHA-256
        }
    }
}
