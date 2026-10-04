package co.com.ticketing.model.order.gateways;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderTransition;
import reactor.core.publisher.Mono;

public interface OrderTransitionGateway {

    /**
     * Atomically persists the new order state, moves the inventory counters and appends an audit record.
     * <ul>
     *     <li>{@code ORDER_STATE_CHANGED} when the order no longer has the version that was read.</li>
     *     <li>{@code INSUFFICIENT_INVENTORY} when the source inventory counter cannot cover the quantity.</li>
     *     <li>A retryable {@code CONCURRENT_UPDATE} when another transaction was touching the same items.</li>
     * </ul>
     */
    Mono<Order> apply(OrderTransition transition);
}
