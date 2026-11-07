package com.kietnguyen.booking.seat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Picks the best block of {@code k} adjacent available seats, the way a box office would:
 * closer to the stage is better, and closer to the middle of the row is better.
 *
 * <p>For each row the seats are scanned once with a sliding window of width {@code k}: the window
 * is valid when all its seats are available and their numbers are consecutive (no aisle or
 * missing seat inside). That is O(n) over all n seats, instead of checking every subset.
 *
 * <p>Score of a window = {@code rowIndex * ROW_WEIGHT + |windowCenter - rowCenter|}. The lowest
 * score wins; ties go to the front row, then to the leftmost window.
 */
public final class BestSeats {

    /** One row further back costs as much as being two seats further from the centre. */
    static final double ROW_WEIGHT = 2.0;

    private BestSeats() {
    }

    /** @param seats the event's seat map, ordered by row label and seat number */
    public static Optional<List<SeatView>> choose(List<SeatView> seats, int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }
        Map<String, List<SeatView>> rows = new LinkedHashMap<>();
        for (SeatView s : seats) {
            rows.computeIfAbsent(s.rowLabel(), r -> new ArrayList<>()).add(s);
        }

        List<SeatView> best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int rowIndex = 0;
        for (List<SeatView> row : rows.values()) {
            double rowCenter = (row.getFirst().number() + row.getLast().number()) / 2.0;
            int validRun = 0; // length of the current run of available, consecutive seats
            for (int i = 0; i < row.size(); i++) {
                SeatView s = row.get(i);
                boolean continues = i > 0 && row.get(i - 1).number() + 1 == s.number();
                validRun = !s.available() ? 0 : (continues ? validRun + 1 : 1);
                if (validRun >= k) {
                    List<SeatView> window = row.subList(i - k + 1, i + 1);
                    double windowCenter = (window.getFirst().number() + window.getLast().number()) / 2.0;
                    double score = rowIndex * ROW_WEIGHT + Math.abs(windowCenter - rowCenter);
                    if (score < bestScore) { // strict: earlier rows and left windows win ties
                        bestScore = score;
                        best = window;
                    }
                }
            }
            rowIndex++;
        }
        return Optional.ofNullable(best).map(List::copyOf);
    }
}
