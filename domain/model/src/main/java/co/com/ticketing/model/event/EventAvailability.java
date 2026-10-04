package co.com.ticketing.model.event;

import java.time.Instant;

public record EventAvailability(String eventId, int totalCapacity, Inventory inventory, Instant asOf) {

    public static EventAvailability of(Event event, Instant now) {
        return new EventAvailability(event.id(), event.totalCapacity(), event.inventory(), now);
    }
}
