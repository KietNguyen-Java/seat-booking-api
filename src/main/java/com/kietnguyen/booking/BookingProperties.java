package com.kietnguyen.booking;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code booking.*} settings from application.yml. */
@ConfigurationProperties(prefix = "booking")
public record BookingProperties(Duration holdDuration, Duration expiryCheckInterval) {
}
