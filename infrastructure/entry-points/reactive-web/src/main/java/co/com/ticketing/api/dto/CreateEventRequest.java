package co.com.ticketing.api.dto;

import co.com.ticketing.model.event.NewEvent;

import java.time.Instant;

public record CreateEventRequest(String name, String venue, Instant startsAt, Integer totalCapacity) {

    public NewEvent toModel() {
        return new NewEvent(name, venue, startsAt, totalCapacity == null ? 0 : totalCapacity);
    }
}
