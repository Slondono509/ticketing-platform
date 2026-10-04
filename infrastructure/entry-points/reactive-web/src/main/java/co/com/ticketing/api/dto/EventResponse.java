package co.com.ticketing.api.dto;

import co.com.ticketing.model.event.Event;

import java.time.Instant;

public record EventResponse(
        String id,
        String name,
        String venue,
        Instant startsAt,
        int totalCapacity,
        int availableTickets,
        Instant createdAt) {

    public static EventResponse from(Event event) {
        return new EventResponse(event.id(), event.name(), event.venue(), event.startsAt(), event.totalCapacity(),
                event.inventory().available(), event.createdAt());
    }
}
