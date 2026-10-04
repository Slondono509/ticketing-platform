package co.com.ticketing.model.order;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderValueObjectsTest {

    private static final ReservationPolicy POLICY = new ReservationPolicy(Duration.ofMinutes(10), 10);

    @Test
    void orderIdIsDeterministicPerCustomerKeyAndType() {
        var placement = new OrderPlacement("customer-1", "event-1", 2, "key-1", OrderType.PURCHASE);

        assertThat(placement.orderId())
                .isEqualTo(new OrderPlacement("customer-1", "event-9", 5, "key-1", OrderType.PURCHASE).orderId())
                .isNotEqualTo(new OrderPlacement("customer-2", "event-1", 2, "key-1", OrderType.PURCHASE).orderId())
                .isNotEqualTo(new OrderPlacement("customer-1", "event-1", 2, "key-2", OrderType.PURCHASE).orderId())
                .isNotEqualTo(new OrderPlacement("customer-1", "event-1", 2, "key-1", OrderType.COMPLIMENTARY).orderId());
    }

    @Test
    void placementValidation() {
        new OrderPlacement("customer-1", "event-1", 10, "key-1", OrderType.PURCHASE).validate(POLICY);

        assertInvalid(new OrderPlacement(null, "event-1", 1, "key-1", OrderType.PURCHASE));
        assertInvalid(new OrderPlacement("customer 1", "event-1", 1, "key-1", OrderType.PURCHASE));
        assertInvalid(new OrderPlacement("customer-1", "event/1", 1, "key-1", OrderType.PURCHASE));
        assertInvalid(new OrderPlacement("customer-1", "event-1", 1, "k".repeat(65), OrderType.PURCHASE));
        assertInvalid(new OrderPlacement("customer-1", "event-1", 0, "key-1", OrderType.PURCHASE));
        assertInvalid(new OrderPlacement("customer-1", "event-1", 11, "key-1", OrderType.PURCHASE));
    }

    @Test
    void reservationPolicyEnforcesTheTenMinuteLimit() {
        assertThat(new ReservationPolicy(Duration.ofMinutes(10), 1).reservationTtl()).hasMinutes(10);
        assertThatThrownBy(() -> new ReservationPolicy(Duration.ofMinutes(11), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationPolicy(Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationPolicy(Duration.ofSeconds(-1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationPolicy(Duration.ofMinutes(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inventoryDeltaRequiresAPositiveQuantity() {
        assertThatThrownBy(() -> new InventoryDelta(TicketStatus.AVAILABLE, TicketStatus.RESERVED, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandsMatchTheOrderType() {
        var now = Instant.parse("2026-10-01T10:00:00Z");
        var purchase = Order.request(new OrderPlacement("c", "e", 1, "k", OrderType.PURCHASE), now);
        var complimentary = Order.request(new OrderPlacement("c", "e", 1, "k", OrderType.COMPLIMENTARY), now);

        assertThat(OrderCommand.initialFor(purchase)).isEqualTo(new OrderCommand(purchase.id(), OrderAction.RESERVE, null));
        assertThat(OrderCommand.initialFor(complimentary).action()).isEqualTo(OrderAction.GRANT_COMPLIMENTARY);
        assertThat(OrderCommand.processPayment("o-1", "tok").paymentToken()).isEqualTo("tok");
    }

    private static void assertInvalid(OrderPlacement placement) {
        assertThatThrownBy(() -> placement.validate(POLICY))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getError())
                .isEqualTo(BusinessError.INVALID_REQUEST);
    }
}
