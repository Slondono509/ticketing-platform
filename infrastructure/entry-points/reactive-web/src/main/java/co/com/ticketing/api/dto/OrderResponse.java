package co.com.ticketing.api.dto;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.ticket.TicketStatus;

import java.time.Instant;
import java.util.List;

/**
 * @param status       lifecycle of the order
 * @param ticketStatus state shared by all the tickets of the order (absent while PROCESSING or when REJECTED)
 */
public record OrderResponse(
        String orderId,
        String eventId,
        String type,
        int quantity,
        String status,
        String ticketStatus,
        String statusReason,
        Instant expiresAt,
        List<TicketResponse> tickets,
        Instant createdAt,
        Instant updatedAt) {

    public record TicketResponse(String ticketId, String status) {
    }

    public static OrderResponse from(Order order) {
        var tickets = order.tickets().stream()
                .map(ticket -> new TicketResponse(ticket.id(), ticket.status().name()))
                .toList();
        return new OrderResponse(order.id(), order.eventId(), order.type().name(), order.quantity(),
                order.status().name(), order.status().ticketStatus().map(TicketStatus::name).orElse(null),
                order.statusReason(), order.expiresAt(), tickets, order.createdAt(), order.updatedAt());
    }
}
