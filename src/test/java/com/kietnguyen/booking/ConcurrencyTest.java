package com.kietnguyen.booking;

import com.kietnguyen.booking.hold.HoldDtos.CreateHoldRequest;
import com.kietnguyen.booking.hold.HoldService;
import com.kietnguyen.booking.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Many customers at the same moment, each in its own thread and transaction, against the real
 * database locks. A start latch releases all threads together to maximise contention.
 */
@SpringBootTest(properties = "booking.expiry-check-interval=PT1H")
@Import(TestClockConfig.class)
class ConcurrencyTest {

    @Autowired
    HoldService holds;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void resetSeats() {
        jdbc.update("UPDATE seat SET status = 'AVAILABLE', hold_id = NULL");
        jdbc.update("DELETE FROM seat_hold");
    }

    private interface Attempt {
        void run(int thread) throws Exception;
    }

    /** Runs {@code attempt} on {@code threads} threads at once; returns the unexpected failures. */
    private static List<Throwable> race(int threads, Attempt attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int thread = t;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    attempt.run(thread);
                } catch (ApiException e) {
                    if (e.status() != HttpStatus.CONFLICT) {
                        unexpected.add(e);
                    }
                } catch (Throwable e) {
                    unexpected.add(e);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        return new ArrayList<>(unexpected);
    }

    @Test
    void thirtyCustomersOneSeatExactlyOneWins() throws Exception {
        AtomicInteger winners = new AtomicInteger();
        List<Throwable> unexpected = race(30, thread -> {
            holds.create(1, "same-seat-" + thread, new CreateHoldRequest("c" + thread + "@example.com", List.of(100L), null));
            winners.incrementAndGet();
        });
        assertTrue(unexpected.isEmpty(), "unexpected failures: " + unexpected);
        assertEquals(1, winners.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM seat_hold", Integer.class));
        assertEquals("HELD", jdbc.queryForObject("SELECT status FROM seat WHERE id = 100", String.class));
    }

    @Test
    void overlappingRequestsInAnyOrderNeverDeadlockOrDoubleBook() throws Exception {
        // 40 requests for 2 seats each out of the same 5 seats, listed in random order. Without
        // sorted locking, A-then-B and B-then-A would deadlock.
        List<Long> pool = List.of(61L, 62L, 63L, 64L, 65L);
        AtomicInteger winners = new AtomicInteger();
        List<Throwable> unexpected = race(40, thread -> {
            List<Long> seats = new ArrayList<>(pool);
            Collections.shuffle(seats, new Random(thread));
            holds.create(1, "overlap-" + thread, new CreateHoldRequest("c@example.com", seats.subList(0, 2), null));
            winners.incrementAndGet();
        });
        assertTrue(unexpected.isEmpty(), "unexpected failures: " + unexpected);
        assertTrue(winners.get() >= 1 && winners.get() <= 2, "at most 2 disjoint pairs fit in 5 seats");
        assertNoSeatHeldTwice();
    }

    @Test
    void bestSeatRequestsShareTheHallWithoutCollisions() throws Exception {
        // Event 2: 5 rows x 10 seats. Twelve groups of 4 want the best seats at once; at most
        // 10 groups fit (2 per row).
        AtomicInteger winners = new AtomicInteger();
        List<Throwable> unexpected = race(12, thread -> {
            holds.create(2, "group-" + thread, new CreateHoldRequest("g" + thread + "@example.com", null, 4));
            winners.incrementAndGet();
        });
        assertTrue(unexpected.isEmpty(), "unexpected failures: " + unexpected);
        assertTrue(winners.get() >= 5, "only " + winners.get() + " groups got seats");
        assertEquals(winners.get() * 4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE event_id = 2 AND status = 'HELD'", Integer.class));
        assertNoSeatHeldTwice();
    }

    private void assertNoSeatHeldTwice() {
        // Each hold's seat_count must equal the seats that point to it, and no seat can point to two holds.
        List<Map<String, Object>> mismatches = jdbc.queryForList("""
                SELECT h.id, h.seat_count, COUNT(s.id) AS actual
                FROM seat_hold h LEFT JOIN seat s ON s.hold_id = h.id
                GROUP BY h.id, h.seat_count
                HAVING COUNT(s.id) <> h.seat_count
                """);
        assertTrue(mismatches.isEmpty(), "holds without their seats: " + mismatches);
    }
}
