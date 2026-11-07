package com.kietnguyen.booking;

import com.kietnguyen.booking.seat.BestSeats;
import com.kietnguyen.booking.seat.SeatStatus;
import com.kietnguyen.booking.seat.SeatView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BestSeatsTest {

    /** Rows of seats numbered 1..perRow; {@code taken} lists labels like "A5". */
    private static List<SeatView> hall(String rows, int perRow, Set<String> taken) {
        List<SeatView> seats = new ArrayList<>();
        long id = 1;
        for (char r : rows.toCharArray()) {
            for (int n = 1; n <= perRow; n++) {
                String label = "" + r + n;
                seats.add(new SeatView(id++, String.valueOf(r), n, 100, taken.contains(label) ? SeatStatus.HELD : SeatStatus.AVAILABLE));
            }
        }
        return seats;
    }

    private static List<String> labels(List<SeatView> seats) {
        return seats.stream().map(SeatView::label).toList();
    }

    @Test
    void picksTheMiddleOfTheFrontRow() {
        assertEquals(List.of("A9", "A10", "A11"), labels(BestSeats.choose(hall("ABC", 20, Set.of()), 3).orElseThrow()));
        assertEquals(List.of("A10", "A11"), labels(BestSeats.choose(hall("ABC", 20, Set.of()), 2).orElseThrow()));
    }

    @Test
    void movesAsideWhenTheMiddleIsTaken() {
        List<SeatView> seats = hall("AB", 20, Set.of("A10", "A11"));
        // A7-A9 is 2.5 seats off centre (score 2.5); B9-B11 is one row back (score 2.5). The front row wins the tie.
        assertEquals(List.of("A7", "A8", "A9"), labels(BestSeats.choose(seats, 3).orElseThrow()));
    }

    @Test
    void prefersCentreOfNextRowOverTheEdgeOfThisOne() {
        Set<String> taken = new java.util.HashSet<>();
        for (int n = 4; n <= 17; n++) {
            taken.add("A" + n);
        }
        // Only A1-A3 and A18-A20 are left in row A: 8.5 seats off centre. B9-B11 scores 2 + 0.5.
        assertEquals(List.of("B9", "B10", "B11"), labels(BestSeats.choose(hall("AB", 20, taken), 3).orElseThrow()));
    }

    @Test
    void neverSpansAGapInSeatNumbers() {
        // Seat 4 does not exist (an aisle), so 3-4-5 is not a block even though 3 and 5 are free.
        List<SeatView> seats = new ArrayList<>(hall("A", 6, Set.of("A1", "A2", "A6")));
        seats.removeIf(s -> s.number() == 4);
        assertTrue(BestSeats.choose(seats, 2).isEmpty());
    }

    @Test
    void reportsWhenNoBlockFits() {
        assertTrue(BestSeats.choose(hall("A", 5, Set.of("A3")), 3).isEmpty());
        assertEquals(List.of("A1", "A2"), labels(BestSeats.choose(hall("A", 5, Set.of("A3")), 2).orElseThrow()));
        assertThrows(IllegalArgumentException.class, () -> BestSeats.choose(hall("A", 5, Set.of()), 0));
    }
}
