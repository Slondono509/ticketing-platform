package co.com.ticketing.model.event;

import co.com.ticketing.model.order.InventoryDelta;
import co.com.ticketing.model.ticket.TicketStatus;

/**
 * Ticket counters of an event, one per {@link TicketStatus}. The sum of all counters is always the event capacity.
 */
public record Inventory(int available, int reserved, int pendingConfirmation, int sold, int complimentary) {

    public Inventory {
        if (available < 0 || reserved < 0 || pendingConfirmation < 0 || sold < 0 || complimentary < 0) {
            throw new IllegalArgumentException("Inventory counters cannot be negative");
        }
    }

    public static Inventory of(int capacity) {
        return new Inventory(capacity, 0, 0, 0, 0);
    }

    public int total() {
        return available + reserved + pendingConfirmation + sold + complimentary;
    }

    public int count(TicketStatus status) {
        return switch (status) {
            case AVAILABLE -> available;
            case RESERVED -> reserved;
            case PENDING_CONFIRMATION -> pendingConfirmation;
            case SOLD -> sold;
            case COMPLIMENTARY -> complimentary;
        };
    }

    public boolean hasAvailable(int quantity) {
        return available >= quantity;
    }

    /** Tickets that are temporarily withheld from sale (RESERVED + PENDING_CONFIRMATION). */
    public int onHold() {
        return reserved + pendingConfirmation;
    }

    /**
     * Moves {@code quantity} tickets between two counters. Fails when the source counter does not hold enough
     * tickets, which is the same guard the persistence layer enforces with a conditional write.
     */
    public Inventory apply(InventoryDelta delta) {
        if (count(delta.from()) < delta.quantity()) {
            throw new IllegalStateException("Not enough %s tickets".formatted(delta.from()));
        }
        return with(delta.from(), count(delta.from()) - delta.quantity())
                .with(delta.to(), count(delta.to()) + delta.quantity());
    }

    private Inventory with(TicketStatus status, int value) {
        return switch (status) {
            case AVAILABLE -> new Inventory(value, reserved, pendingConfirmation, sold, complimentary);
            case RESERVED -> new Inventory(available, value, pendingConfirmation, sold, complimentary);
            case PENDING_CONFIRMATION -> new Inventory(available, reserved, value, sold, complimentary);
            case SOLD -> new Inventory(available, reserved, pendingConfirmation, value, complimentary);
            case COMPLIMENTARY -> new Inventory(available, reserved, pendingConfirmation, sold, value);
        };
    }
}
