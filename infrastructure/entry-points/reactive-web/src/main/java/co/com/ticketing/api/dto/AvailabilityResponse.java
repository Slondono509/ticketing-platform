package co.com.ticketing.api.dto;

import co.com.ticketing.model.event.EventAvailability;

import java.time.Instant;

/**
 * @param onHold tickets temporarily withheld (reserved + pending confirmation); they are not sales
 */
public record AvailabilityResponse(
        String eventId,
        int totalCapacity,
        int available,
        int reserved,
        int pendingConfirmation,
        int onHold,
        int sold,
        int complimentary,
        Instant asOf) {

    public static AvailabilityResponse from(EventAvailability availability) {
        var inventory = availability.inventory();
        return new AvailabilityResponse(availability.eventId(), availability.totalCapacity(), inventory.available(),
                inventory.reserved(), inventory.pendingConfirmation(), inventory.onHold(), inventory.sold(),
                inventory.complimentary(), availability.asOf());
    }
}
