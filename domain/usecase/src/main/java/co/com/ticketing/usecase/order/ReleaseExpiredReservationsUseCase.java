package co.com.ticketing.usecase.order;

import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.order.gateways.OrderTransitionGateway;
import co.com.ticketing.usecase.support.RetryPolicies;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Periodically triggered (EventBridge Scheduler → SQS) to return expired reservations to the inventory.
 * <p>
 * Safe to run concurrently with confirmations and with other instances of itself: each release is a conditional
 * write on the order version, so an order that was confirmed (or already released) in the meantime is skipped.
 */
public class ReleaseExpiredReservationsUseCase {

    public static final int BATCH_SIZE = 200;
    private static final int CONCURRENCY = 16;
    private static final System.Logger LOG = System.getLogger(ReleaseExpiredReservationsUseCase.class.getName());

    private final OrderRepository orderRepository;
    private final OrderTransitionGateway transitionGateway;
    private final Clock clock;

    public ReleaseExpiredReservationsUseCase(OrderRepository orderRepository, OrderTransitionGateway transitionGateway,
                                             Clock clock) {
        this.orderRepository = orderRepository;
        this.transitionGateway = transitionGateway;
        this.clock = clock;
    }

    /** @return number of orders released in this run */
    public Mono<Long> releaseExpired() {
        return Mono.defer(() -> {
            var now = clock.instant();
            return orderRepository.findExpiredReservations(now, BATCH_SIZE)
                    .flatMap(order -> release(order, now), CONCURRENCY)
                    .count()
                    .doOnNext(released -> LOG.log(System.Logger.Level.INFO, "Released {0} expired reservations", released));
        });
    }

    private Mono<Order> release(Order order, Instant now) {
        return Mono.defer(() -> transitionGateway.apply(order.release(now, Order.REASON_RESERVATION_EXPIRED)))
                .retryWhen(RetryPolicies.transientFailures())
                .onErrorResume(BusinessException.class, error -> {
                    LOG.log(System.Logger.Level.INFO, "Order {0} skipped: {1}", order.id(), error.getError());
                    return Mono.empty();
                });
    }
}
