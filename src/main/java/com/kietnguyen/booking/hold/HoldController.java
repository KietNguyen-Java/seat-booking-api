package com.kietnguyen.booking.hold;

import com.kietnguyen.booking.hold.HoldDtos.CreateHoldRequest;
import com.kietnguyen.booking.hold.HoldDtos.HoldResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
public class HoldController {

    private final HoldService service;

    public HoldController(HoldService service) {
        this.service = service;
    }

    /**
     * 201 for a new hold; 200 with {@code Idempotent-Replayed: true} when the same
     * Idempotency-Key and body were already processed.
     */
    @PostMapping("/api/events/{eventId}/holds")
    public ResponseEntity<HoldResponse> create(@PathVariable long eventId,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @Valid @RequestBody CreateHoldRequest request) {
        HoldService.Outcome outcome = service.create(eventId, idempotencyKey, request);
        if (outcome.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(outcome.hold());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/holds/" + outcome.hold().id()))
                .body(outcome.hold());
    }

    @GetMapping("/api/holds/{id}")
    public HoldResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/api/holds/{id}/confirm")
    public HoldResponse confirm(@PathVariable UUID id) {
        return service.confirm(id);
    }

    @DeleteMapping("/api/holds/{id}")
    public HoldResponse release(@PathVariable UUID id) {
        return service.release(id);
    }
}
