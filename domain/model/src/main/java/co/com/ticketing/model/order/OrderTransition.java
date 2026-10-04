package co.com.ticketing.model.order;

import java.time.Instant;
import java.util.Optional;

/**
 * A state change of an order together with its effect on the event inventory. It must be persisted atomically:
 * either the order, the inventory counters and the audit record change together, or nothing changes.
 *
 * @param previous       order as it was read; its version is the optimistic-locking guard
 * @param next           order after the transition
 * @param inventoryDelta counters to move, or {@code null} when the transition does not touch the inventory
 */
public record OrderTransition(Order previous, Order next, InventoryDelta inventoryDelta, String reason,
                              Instant occurredAt) {

    public Optional<InventoryDelta> inventoryChange() {
        return Optional.ofNullable(inventoryDelta);
    }
}
