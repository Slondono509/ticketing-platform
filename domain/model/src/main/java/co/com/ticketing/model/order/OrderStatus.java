package co.com.ticketing.model.order;

import co.com.ticketing.model.ticket.TicketStatus;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Lifecycle of an order. Besides the states shared with its tickets, an order has two states of its own:
 * {@code PROCESSING} (accepted and queued, inventory not touched yet) and {@code REJECTED} (no inventory was
 * available). {@code RELEASED} means its tickets went back to {@code AVAILABLE}.
 */
public enum OrderStatus {
    PROCESSING(null),
    RESERVED(TicketStatus.RESERVED),
    PENDING_CONFIRMATION(TicketStatus.PENDING_CONFIRMATION),
    SOLD(TicketStatus.SOLD),
    COMPLIMENTARY(TicketStatus.COMPLIMENTARY),
    RELEASED(TicketStatus.AVAILABLE),
    REJECTED(null);

    private final TicketStatus ticketStatus;

    OrderStatus(TicketStatus ticketStatus) {
        this.ticketStatus = ticketStatus;
    }

    /** State of the tickets held by an order in this state, empty when the order holds no tickets. */
    public Optional<TicketStatus> ticketStatus() {
        return Optional.ofNullable(ticketStatus);
    }

    public Set<OrderStatus> allowedTransitions() {
        return switch (this) {
            case PROCESSING -> EnumSet.of(RESERVED, COMPLIMENTARY, REJECTED);
            case RESERVED -> EnumSet.of(PENDING_CONFIRMATION, RELEASED);
            case PENDING_CONFIRMATION -> EnumSet.of(SOLD, RELEASED);
            case SOLD, COMPLIMENTARY, RELEASED, REJECTED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isFinal() {
        return allowedTransitions().isEmpty();
    }
}
