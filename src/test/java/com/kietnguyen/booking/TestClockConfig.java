package com.kietnguyen.booking;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move forward, to make holds expire without waiting. */
@TestConfiguration
public class TestClockConfig {

    public static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-12-01T10:00:00Z");

        public void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
