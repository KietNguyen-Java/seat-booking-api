package com.kietnguyen.booking.event;

import com.kietnguyen.booking.seat.SeatRepository;
import com.kietnguyen.booking.seat.SeatStatus;
import com.kietnguyen.booking.seat.SeatView;
import com.kietnguyen.booking.web.ApiException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/events")
public class EventController {

    public record EventResponse(long id, String name, String venue, OffsetDateTime startsAt) {
    }

    public record SeatResponse(long id, int number, SeatStatus status, long priceVnd) {
    }

    public record RowResponse(String row, List<SeatResponse> seats) {
    }

    public record SeatMapResponse(long eventId, String name, long available, List<RowResponse> rows) {
    }

    private final EventRepository events;
    private final SeatRepository seats;

    public EventController(EventRepository events, SeatRepository seats) {
        this.events = events;
        this.seats = seats;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<EventResponse> list() {
        return events.findAllByOrderByStartsAtAsc().stream()
                .map(e -> new EventResponse(e.getId(), e.getName(), e.getVenue(), e.getStartsAt()))
                .toList();
    }

    @GetMapping("/{id}/seats")
    @Transactional(readOnly = true)
    public SeatMapResponse seatMap(@PathVariable long id) {
        Event event = events.findById(id).orElseThrow(() -> ApiException.notFound("No event with id " + id));
        List<SeatView> all = seats.seatMap(id);
        Map<String, List<SeatResponse>> rows = new LinkedHashMap<>();
        for (SeatView s : all) {
            rows.computeIfAbsent(s.rowLabel(), r -> new ArrayList<>())
                    .add(new SeatResponse(s.id(), s.number(), s.status(), s.priceVnd()));
        }
        long available = all.stream().filter(SeatView::available).count();
        List<RowResponse> rowList = rows.entrySet().stream().map(e -> new RowResponse(e.getKey(), e.getValue())).toList();
        return new SeatMapResponse(id, event.getName(), available, rowList);
    }
}
