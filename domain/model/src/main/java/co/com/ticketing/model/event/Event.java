package co.com.ticketing.model.event;

import java.time.Instant;
import java.util.Objects;

public record Event(
        String id,
        String name,
        String venue,
        Instant startsAt,
        int totalCapacity,
        Inventory inventory,
        Instant createdAt,
        long version) {

    public Event {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(inventory, "inventory");
    }

    public static Event create(String id, NewEvent data, Instant now) {
        return new Event(id, data.name().strip(), data.venue().strip(), data.startsAt(), data.totalCapacity(),
                Inventory.of(data.totalCapacity()), now, 0L);
    }

    public boolean hasStarted(Instant now) {
        return !startsAt.isAfter(now);
    }
}
