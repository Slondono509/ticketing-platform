package co.com.ticketing.usecase.concurrency;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.order.gateways.OrderTransitionGateway;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test double that reproduces the guarantees of the DynamoDB transaction used in production: the order is only
 * written if its status and version did not change, and the inventory counter is only moved if it covers the
 * quantity. The whole state is swapped with a compare-and-set, so a transition is atomic and lock-free (no
 * blocking calls that BlockHound would flag).
 */
class InMemoryTicketStore implements OrderRepository, OrderTransitionGateway {

    private record State(Map<String, Event> events, Map<String, Order> orders) {
    }

    private final AtomicReference<State> state = new AtomicReference<>(new State(Map.of(), Map.of()));

    void put(Event event) {
        state.updateAndGet(current -> {
            var events = new HashMap<>(current.events());
            events.put(event.id(), event);
            return new State(Map.copyOf(events), current.orders());
        });
    }

    Event event(String id) {
        return state.get().events().get(id);
    }

    Map<String, Order> orders() {
        return state.get().orders();
    }

    @Override
    public Mono<Order> create(Order order) {
        return Mono.fromCallable(() -> {
            state.updateAndGet(current -> {
                if (current.orders().containsKey(order.id())) {
                    throw new BusinessException(BusinessError.ORDER_ALREADY_EXISTS);
                }
                return withOrder(current, order);
            });
            return order;
        });
    }

    @Override
    public Mono<Order> findById(String id) {
        return Mono.fromSupplier(() -> state.get().orders().get(id));
    }

    @Override
    public Flux<Order> findExpiredReservations(Instant now, int limit) {
        return Flux.fromStream(() -> state.get().orders().values().stream()
                .filter(order -> order.status() == OrderStatus.RESERVED && order.isReservationExpired(now))
                .limit(limit));
    }

    @Override
    public Mono<Order> apply(OrderTransition transition) {
        return Mono.fromCallable(() -> {
            while (true) {
                var current = state.get();
                var stored = current.orders().get(transition.previous().id());
                if (stored == null || stored.version() != transition.previous().version()
                        || stored.status() != transition.previous().status()) {
                    throw new BusinessException(BusinessError.ORDER_STATE_CHANGED);
                }
                var next = withOrder(current, transition.next());
                if (transition.inventoryChange().isPresent()) {
                    var delta = transition.inventoryChange().get();
                    var event = current.events().get(transition.next().eventId());
                    if (event.inventory().count(delta.from()) < delta.quantity()) {
                        throw new BusinessException(BusinessError.INSUFFICIENT_INVENTORY);
                    }
                    var updated = new Event(event.id(), event.name(), event.venue(), event.startsAt(),
                            event.totalCapacity(), event.inventory().apply(delta), event.createdAt(), event.version() + 1);
                    var events = new HashMap<>(next.events());
                    events.put(updated.id(), updated);
                    next = new State(Map.copyOf(events), next.orders());
                }
                if (state.compareAndSet(current, next)) {
                    return transition.next();
                }
            }
        });
    }

    private static State withOrder(State current, Order order) {
        var orders = new HashMap<>(current.orders());
        orders.put(order.id(), order);
        return new State(current.events(), Map.copyOf(orders));
    }
}
