package co.com.ticketing.model.order;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.ticket.Ticket;
import co.com.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);

    private static Order newOrder(OrderType type, int quantity) {
        return Order.request(new OrderPlacement("customer-1", "event-1", quantity, "key-1", type), NOW);
    }

    @Test
    void requestCreatesAProcessingOrderWithoutTickets() {
        var order = newOrder(OrderType.PURCHASE, 2);

        assertThat(order.status()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(order.tickets()).isEmpty();
        assertThat(order.version()).isZero();
        assertThat(order.id()).isEqualTo(new OrderPlacement("customer-1", "event-1", 2, "key-1",
                OrderType.PURCHASE).orderId());
    }

    @Test
    void reserveHoldsTicketsAndMovesAvailableToReserved() {
        var transition = newOrder(OrderType.PURCHASE, 3).reserve(NOW, TTL);
        var reserved = transition.next();

        assertThat(reserved.status()).isEqualTo(OrderStatus.RESERVED);
        assertThat(reserved.tickets()).hasSize(3).extracting(Ticket::status).containsOnly(TicketStatus.RESERVED);
        assertThat(reserved.tickets()).extracting(Ticket::id).doesNotHaveDuplicates();
        assertThat(reserved.expiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(reserved.version()).isEqualTo(1);
        assertThat(transition.inventoryChange())
                .contains(new InventoryDelta(TicketStatus.AVAILABLE, TicketStatus.RESERVED, 3));
        assertThat(transition.previous().status()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(transition.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void fullPurchaseLifecycle() {
        var reserved = newOrder(OrderType.PURCHASE, 2).reserve(NOW, TTL).next();

        var confirming = reserved.requestConfirmation(NOW.plusSeconds(60));
        var sold = confirming.next().completeSale(NOW.plusSeconds(90));

        assertThat(confirming.next().status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
        assertThat(confirming.next().expiresAt()).isNull();
        assertThat(confirming.inventoryChange())
                .contains(new InventoryDelta(TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION, 2));
        assertThat(sold.next().status()).isEqualTo(OrderStatus.SOLD);
        assertThat(sold.next().tickets()).extracting(Ticket::status).containsOnly(TicketStatus.SOLD);
        assertThat(sold.inventoryChange())
                .contains(new InventoryDelta(TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD, 2));
        assertThat(sold.next().status().isFinal()).isTrue();
    }

    @Test
    void releaseReturnsTicketsToTheInventory() {
        var reserved = newOrder(OrderType.PURCHASE, 2).reserve(NOW, TTL).next();

        var released = reserved.release(NOW.plus(TTL), Order.REASON_RESERVATION_EXPIRED);

        assertThat(released.next().status()).isEqualTo(OrderStatus.RELEASED);
        assertThat(released.next().statusReason()).isEqualTo(Order.REASON_RESERVATION_EXPIRED);
        assertThat(released.next().tickets()).extracting(Ticket::status).containsOnly(TicketStatus.AVAILABLE);
        assertThat(released.inventoryChange())
                .contains(new InventoryDelta(TicketStatus.RESERVED, TicketStatus.AVAILABLE, 2));
        assertThat(released.reason()).isEqualTo(Order.REASON_RESERVATION_EXPIRED);
    }

    @Test
    void declinedPaymentReleasesPendingTickets() {
        var pending = newOrder(OrderType.PURCHASE, 1).reserve(NOW, TTL).next().requestConfirmation(NOW).next();

        var released = pending.release(NOW, Order.REASON_PAYMENT_DECLINED);

        assertThat(released.inventoryChange())
                .contains(new InventoryDelta(TicketStatus.PENDING_CONFIRMATION, TicketStatus.AVAILABLE, 1));
    }

    @Test
    void complimentaryOrdersGoStraightToAFinalNonAccountableState() {
        var transition = newOrder(OrderType.COMPLIMENTARY, 2).grantComplimentary(NOW);

        assertThat(transition.next().status()).isEqualTo(OrderStatus.COMPLIMENTARY);
        assertThat(transition.next().tickets()).extracting(Ticket::status).containsOnly(TicketStatus.COMPLIMENTARY);
        assertThat(transition.inventoryChange())
                .contains(new InventoryDelta(TicketStatus.AVAILABLE, TicketStatus.COMPLIMENTARY, 2));
    }

    @Test
    void rejectDoesNotTouchTheInventory() {
        var transition = newOrder(OrderType.PURCHASE, 2).reject(NOW, Order.REASON_SOLD_OUT);

        assertThat(transition.next().status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(transition.next().statusReason()).isEqualTo(Order.REASON_SOLD_OUT);
        assertThat(transition.inventoryChange()).isEmpty();
    }

    @Test
    void expiredReservationCannotBeConfirmed() {
        var reserved = newOrder(OrderType.PURCHASE, 1).reserve(NOW, TTL).next();

        assertThat(reserved.isReservationExpired(NOW.plus(TTL).minusMillis(1))).isFalse();
        assertThat(reserved.isReservationExpired(NOW.plus(TTL))).isTrue();
        assertBusinessError(() -> reserved.requestConfirmation(NOW.plus(TTL)), BusinessError.RESERVATION_EXPIRED);
    }

    @Test
    void invalidTransitionsAreRejected() {
        var processing = newOrder(OrderType.PURCHASE, 1);
        var sold = processing.reserve(NOW, TTL).next().requestConfirmation(NOW).next().completeSale(NOW).next();

        assertBusinessError(() -> processing.completeSale(NOW), BusinessError.INVALID_STATE_TRANSITION);
        assertBusinessError(() -> processing.requestConfirmation(NOW), BusinessError.INVALID_STATE_TRANSITION);
        assertBusinessError(() -> sold.release(NOW, "x"), BusinessError.INVALID_STATE_TRANSITION);
        assertBusinessError(() -> processing.grantComplimentary(NOW), BusinessError.INVALID_STATE_TRANSITION);
        assertBusinessError(() -> newOrder(OrderType.COMPLIMENTARY, 1).reserve(NOW, TTL),
                BusinessError.INVALID_STATE_TRANSITION);
    }

    @Test
    void ownershipAndReplayMatching() {
        var order = newOrder(OrderType.PURCHASE, 2);

        assertThat(order.isOwnedBy("customer-1")).isTrue();
        assertThat(order.isOwnedBy("customer-2")).isFalse();
        assertThat(order.matches(new OrderPlacement("customer-1", "event-1", 2, "key-1", OrderType.PURCHASE))).isTrue();
        assertThat(order.matches(new OrderPlacement("customer-1", "event-1", 3, "key-1", OrderType.PURCHASE))).isFalse();
        assertThat(order.matches(new OrderPlacement("customer-1", "event-2", 2, "key-1", OrderType.PURCHASE))).isFalse();
        assertThat(order.matches(new OrderPlacement("customer-1", "event-1", 2, "key-1", OrderType.COMPLIMENTARY)))
                .isFalse();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void orderStatusesExposeTheirTicketStatus(OrderStatus status) {
        var expectsTickets = status != OrderStatus.PROCESSING && status != OrderStatus.REJECTED;

        assertThat(status.ticketStatus().isPresent()).isEqualTo(expectsTickets);
        assertThat(status.isFinal()).isEqualTo(status.allowedTransitions().isEmpty());
    }

    @Test
    void nullTicketListIsNormalized() {
        var order = new Order("o-1", "e-1", "c-1", 1, OrderType.PURCHASE, OrderStatus.PROCESSING, null, null, null,
                NOW, NOW, 0);

        assertThat(order.tickets()).isEmpty();
    }

    private static void assertBusinessError(Runnable action, BusinessError expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getError())
                .isEqualTo(expected);
    }
}
