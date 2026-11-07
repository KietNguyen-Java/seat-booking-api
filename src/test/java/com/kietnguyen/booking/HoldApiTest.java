package com.kietnguyen.booking;

import com.kietnguyen.booking.hold.HoldService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The booking flow over HTTP. Seed: event 1 has rows A-J x 20 seats (ids 1-200, A1 = 1, A2 = 2...);
 * rows A-C cost 800,000 VND.
 */
@SpringBootTest(properties = "booking.expiry-check-interval=PT1H") // the test drives expiry itself
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class HoldApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestClockConfig.MutableClock clock;
    @Autowired
    HoldService holds;

    @AfterEach
    void resetSeats() {
        jdbc.update("UPDATE seat SET status = 'AVAILABLE', hold_id = NULL");
        jdbc.update("DELETE FROM seat_hold");
    }

    private ResultActions hold(String key, String body) throws Exception {
        return mvc.perform(post("/api/events/1/holds").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String holdId(String key, String body) throws Exception {
        String json = hold(key, body).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void holdsChosenSeats() throws Exception {
        hold("k1", """
                {"customerEmail": "An@Example.com", "seatIds": [2, 1]}""")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/holds/")))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.seats[0]").value("A1"))
                .andExpect(jsonPath("$.seats[1]").value("A2"))
                .andExpect(jsonPath("$.totalPriceVnd").value(1_600_000));

        mvc.perform(get("/api/events/1/seats"))
                .andExpect(jsonPath("$.available").value(198))
                .andExpect(jsonPath("$.rows[0].seats[0].status").value("HELD"));
    }

    @Test
    void picksBestAdjacentSeatsForAQuantity() throws Exception {
        hold("k2", """
                {"customerEmail": "binh@example.com", "quantity": 3}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seats[0]").value("A9"))
                .andExpect(jsonPath("$.seats[2]").value("A11"));
        hold("k3", """
                {"customerEmail": "chi@example.com", "quantity": 3}""")
                .andExpect(jsonPath("$.seats[0]").value("A12"));
    }

    @Test
    void retryWithSameKeyReturnsTheSameHold() throws Exception {
        String body = """
                {"customerEmail": "an@example.com", "seatIds": [5]}""";
        String id = holdId("retry-1", body);
        hold("retry-1", body)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM seat_hold", Integer.class));

        hold("retry-1", """
                {"customerEmail": "an@example.com", "seatIds": [6]}""")
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void refusesSeatsThatAreAlreadyTaken() throws Exception {
        holdId("first", """
                {"customerEmail": "a@example.com", "seatIds": [10, 11]}""");
        hold("second", """
                {"customerEmail": "b@example.com", "seatIds": [11, 12]}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Seats already taken: A11"));
        // Nothing was held for the failed request, not even the free seat A12.
        mvc.perform(get("/api/events/1/seats")).andExpect(jsonPath("$.available").value(198));
    }

    @Test
    void confirmSellsTheSeatsAndIsIdempotent() throws Exception {
        String id = holdId("pay", """
                {"customerEmail": "a@example.com", "seatIds": [21]}""");
        mvc.perform(post("/api/holds/" + id + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());
        mvc.perform(post("/api/holds/" + id + "/confirm")).andExpect(status().isOk());
        mvc.perform(get("/api/events/1/seats")).andExpect(jsonPath("$.rows[1].seats[0].status").value("SOLD"));
        mvc.perform(delete("/api/holds/" + id)).andExpect(status().isConflict());
    }

    @Test
    void releaseFreesTheSeats() throws Exception {
        String id = holdId("rel", """
                {"customerEmail": "a@example.com", "seatIds": [30, 31]}""");
        mvc.perform(delete("/api/holds/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mvc.perform(get("/api/events/1/seats")).andExpect(jsonPath("$.available").value(200));
        mvc.perform(post("/api/holds/" + id + "/confirm")).andExpect(status().isConflict());
    }

    @Test
    void expiredHoldCannotBePaidAndItsSeatsReturn() throws Exception {
        String id = holdId("slow", """
                {"customerEmail": "a@example.com", "seatIds": [40]}""");
        clock.advance(Duration.ofMinutes(6)); // holds last 5 minutes

        mvc.perform(post("/api/holds/" + id + "/confirm")).andExpect(status().isGone());
        assertEquals(1, holds.expireDue());
        mvc.perform(get("/api/holds/" + id)).andExpect(jsonPath("$.status").value("EXPIRED"));
        mvc.perform(get("/api/events/1/seats")).andExpect(jsonPath("$.available").value(200));
        assertEquals(0, holds.expireDue());
    }

    @Test
    void validatesRequests() throws Exception {
        mvc.perform(post("/api/events/1/holds").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\": \"a@example.com\", \"seatIds\": [1]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Missing header Idempotency-Key"));
        hold("v1", "{\"customerEmail\": \"a@example.com\", \"seatIds\": [1], \"quantity\": 2}").andExpect(status().isBadRequest());
        hold("v2", "{\"customerEmail\": \"a@example.com\"}").andExpect(status().isBadRequest());
        hold("v3", "{\"customerEmail\": \"not-an-email\", \"seatIds\": [1]}").andExpect(status().isBadRequest());
        hold("v4", "{\"customerEmail\": \"a@example.com\", \"quantity\": 20}").andExpect(status().isBadRequest());
        hold("v5", "{\"customerEmail\": \"a@example.com\", \"seatIds\": [999]}").andExpect(status().isNotFound());
        mvc.perform(post("/api/events/9/holds").header("Idempotency-Key", "v6").contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerEmail\": \"a@example.com\", \"seatIds\": [1]}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/holds/00000000-0000-0000-0000-000000000000")).andExpect(status().isNotFound());
    }
}
