package co.com.ticketing.model.event;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.order.InventoryDelta;
import co.com.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant FUTURE = NOW.plus(Duration.ofDays(10));

    @Test
    void createStartsWithTheWholeCapacityAvailable() {
        var event = Event.create("e-1", new NewEvent("  Rock Fest ", " Arena ", FUTURE, 500), NOW);

        assertThat(event.name()).isEqualTo("Rock Fest");
        assertThat(event.venue()).isEqualTo("Arena");
        assertThat(event.inventory()).isEqualTo(new Inventory(500, 0, 0, 0, 0));
        assertThat(event.version()).isZero();
        assertThat(event.hasStarted(NOW)).isFalse();
        assertThat(event.hasStarted(FUTURE)).isTrue();
    }

    @Test
    void validNewEventPassesValidation() {
        new NewEvent("Rock Fest", "Arena", FUTURE, 10).validate(NOW);
    }

    @Test
    void newEventRejectsInvalidData() {
        assertInvalid(new NewEvent(null, "Arena", FUTURE, 10));
        assertInvalid(new NewEvent(" ", "Arena", FUTURE, 10));
        assertInvalid(new NewEvent("x".repeat(NewEvent.MAX_TEXT_LENGTH + 1), "Arena", FUTURE, 10));
        assertInvalid(new NewEvent("Rock", null, FUTURE, 10));
        assertInvalid(new NewEvent("Rock", "Arena", null, 10));
        assertInvalid(new NewEvent("Rock", "Arena", NOW, 10));
        assertInvalid(new NewEvent("Rock", "Arena", FUTURE, 0));
        assertInvalid(new NewEvent("Rock", "Arena", FUTURE, NewEvent.MAX_CAPACITY + 1));
    }

    @Test
    void inventoryAppliesDeltasKeepingTheTotal() {
        var inventory = Inventory.of(10)
                .apply(new InventoryDelta(TicketStatus.AVAILABLE, TicketStatus.RESERVED, 4))
                .apply(new InventoryDelta(TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION, 4))
                .apply(new InventoryDelta(TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD, 4))
                .apply(new InventoryDelta(TicketStatus.AVAILABLE, TicketStatus.COMPLIMENTARY, 2));

        assertThat(inventory).isEqualTo(new Inventory(4, 0, 0, 4, 2));
        assertThat(inventory.total()).isEqualTo(10);
        assertThat(inventory.hasAvailable(4)).isTrue();
        assertThat(inventory.hasAvailable(5)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void inventoryCountsEveryStatus(TicketStatus status) {
        var inventory = new Inventory(1, 2, 3, 4, 5);

        assertThat(inventory.count(status)).isEqualTo(status.ordinal() + 1);
        assertThat(inventory.onHold()).isEqualTo(5);
    }

    @Test
    void inventoryNeverGoesNegative() {
        var inventory = Inventory.of(1);
        var delta = new InventoryDelta(TicketStatus.AVAILABLE, TicketStatus.RESERVED, 2);

        assertThatThrownBy(() -> inventory.apply(delta)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Inventory(-1, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void availabilitySnapshotsTheInventory() {
        var event = Event.create("e-1", new NewEvent("Rock", "Arena", FUTURE, 5), NOW);

        var availability = EventAvailability.of(event, NOW);

        assertThat(availability.eventId()).isEqualTo("e-1");
        assertThat(availability.totalCapacity()).isEqualTo(5);
        assertThat(availability.inventory()).isEqualTo(event.inventory());
        assertThat(availability.asOf()).isEqualTo(NOW);
    }

    private static void assertInvalid(NewEvent event) {
        assertThatThrownBy(() -> event.validate(NOW))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getError())
                .isEqualTo(BusinessError.INVALID_REQUEST);
    }
}
