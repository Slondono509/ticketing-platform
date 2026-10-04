package co.com.ticketing.model.order;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Aggregate root of the purchase process. It owns its tickets, so every ticket of an order always shares the
 * order's lifecycle. Instances are immutable: every state change returns an {@link OrderTransition} that the
 * persistence layer applies atomically.
 */
public record Order(
        String id,
        String eventId,
        String customerId,
        int quantity,
        OrderType type,
        OrderStatus status,
        List<Ticket> tickets,
        Instant expiresAt,
        String statusReason,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static final String REASON_SOLD_OUT = "SOLD_OUT";
    public static final String REASON_RESERVATION_EXPIRED = "RESERVATION_EXPIRED";
    public static final String REASON_PAYMENT_DECLINED = "PAYMENT_DECLINED";

    public Order {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        tickets = tickets == null ? List.of() : List.copyOf(tickets);
    }

    public static Order request(OrderPlacement request, Instant now) {
        return new Order(request.orderId(), request.eventId(), request.customerId(), request.quantity(),
                request.type(), OrderStatus.PROCESSING, List.of(), null, null, now, now, 0L);
    }

    /** Holds the tickets for the customer during {@code ttl}. */
    public OrderTransition reserve(Instant now, Duration ttl) {
        requireType(OrderType.PURCHASE);
        var reserved = newTickets().stream().map(ticket -> ticket.transitionTo(TicketStatus.RESERVED)).toList();
        return moveTo(OrderStatus.RESERVED, reserved, now.plus(ttl), null, now);
    }

    public OrderTransition grantComplimentary(Instant now) {
        requireType(OrderType.COMPLIMENTARY);
        var granted = newTickets().stream().map(ticket -> ticket.transitionTo(TicketStatus.COMPLIMENTARY)).toList();
        return moveTo(OrderStatus.COMPLIMENTARY, granted, null, null, now);
    }

    public OrderTransition reject(Instant now, String reason) {
        return moveTo(OrderStatus.REJECTED, List.of(), null, reason, now);
    }

    /** The customer starts paying: tickets leave the expirable RESERVED state. */
    public OrderTransition requestConfirmation(Instant now) {
        if (status == OrderStatus.RESERVED && isReservationExpired(now)) {
            throw new BusinessException(BusinessError.RESERVATION_EXPIRED);
        }
        return moveTo(OrderStatus.PENDING_CONFIRMATION, ticketsIn(TicketStatus.PENDING_CONFIRMATION), null, null, now);
    }

    public OrderTransition completeSale(Instant now) {
        return moveTo(OrderStatus.SOLD, ticketsIn(TicketStatus.SOLD), null, null, now);
    }

    /** Returns the tickets to the inventory (reservation expired or payment declined). */
    public OrderTransition release(Instant now, String reason) {
        return moveTo(OrderStatus.RELEASED, ticketsIn(TicketStatus.AVAILABLE), null, reason, now);
    }

    public boolean isReservationExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean isOwnedBy(String candidateCustomerId) {
        return customerId.equals(candidateCustomerId);
    }

    /** A replayed request must carry the same payload as the one that created the order. */
    public boolean matches(OrderPlacement request) {
        return eventId.equals(request.eventId()) && quantity == request.quantity() && type == request.type();
    }

    private OrderTransition moveTo(OrderStatus target, List<Ticket> nextTickets, Instant nextExpiresAt,
                                   String reason, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(BusinessError.INVALID_STATE_TRANSITION,
                    "Order %s cannot move from %s to %s".formatted(id, status, target));
        }
        var next = new Order(id, eventId, customerId, quantity, type, target, nextTickets, nextExpiresAt, reason,
                createdAt, now, version + 1);
        return new OrderTransition(this, next, inventoryDelta(target), reason, now);
    }

    private InventoryDelta inventoryDelta(OrderStatus target) {
        var from = status.ticketStatus().orElse(TicketStatus.AVAILABLE);
        return target.ticketStatus()
                .filter(to -> to != from)
                .map(to -> new InventoryDelta(from, to, quantity))
                .orElse(null);
    }

    /**
     * Ticket ids are derived from the order id, so they are stable across retries and their generation does not
     * touch {@code SecureRandom} (which may block on I/O inside reactive threads).
     */
    private List<Ticket> newTickets() {
        return IntStream.rangeClosed(1, quantity)
                .mapToObj(number -> UUID.nameUUIDFromBytes((id + "#" + number).getBytes(StandardCharsets.UTF_8)))
                .map(ticketId -> new Ticket(ticketId.toString(), TicketStatus.AVAILABLE))
                .toList();
    }

    private List<Ticket> ticketsIn(TicketStatus target) {
        return tickets.stream().map(ticket -> ticket.transitionTo(target)).toList();
    }

    private void requireType(OrderType expected) {
        if (type != expected) {
            throw new BusinessException(BusinessError.INVALID_STATE_TRANSITION,
                    "Order %s of type %s cannot be processed as %s".formatted(id, type, expected));
        }
    }
}
