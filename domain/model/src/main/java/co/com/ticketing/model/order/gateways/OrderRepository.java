package co.com.ticketing.model.order.gateways;

import co.com.ticketing.model.order.Order;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface OrderRepository {

    /** Persists a new order. Fails with {@code ORDER_ALREADY_EXISTS} if the id is taken. */
    Mono<Order> create(Order order);

    Mono<Order> findById(String id);

    /** Reserved orders whose reservation expired before {@code now}, at most {@code limit} per call. */
    Flux<Order> findExpiredReservations(Instant now, int limit);
}
