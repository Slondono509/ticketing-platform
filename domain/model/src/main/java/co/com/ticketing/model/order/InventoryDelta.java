package co.com.ticketing.model.order;

import co.com.ticketing.model.ticket.TicketStatus;

import java.util.Objects;

/**
 * Moves {@code quantity} tickets of an event from one inventory counter to another.
 */
public record InventoryDelta(TicketStatus from, TicketStatus to, int quantity) {

    public InventoryDelta {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
