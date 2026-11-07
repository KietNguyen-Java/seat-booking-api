package com.kietnguyen.booking.hold;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Frees the seats of holds nobody paid for in time. */
@Component
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final HoldService holds;

    public HoldExpiryJob(HoldService holds) {
        this.holds = holds;
    }

    @Scheduled(fixedDelayString = "${booking.expiry-check-interval}")
    public void run() {
        int expired;
        do {
            expired = holds.expireDue(); // batches of 100, each in its own short transaction
            if (expired > 0) {
                log.info("Expired {} holds and released their seats", expired);
            }
        } while (expired == 100);
    }
}
