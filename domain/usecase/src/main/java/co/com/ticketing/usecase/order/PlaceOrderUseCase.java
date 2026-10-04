package co.com.ticketing.usecase.order;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderCommand;
import co.com.ticketing.model.order.OrderPlacement;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.ReservationPolicy;
import co.com.ticketing.model.order.gateways.OrderCommandPublisher;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.usecase.support.RetryPolicies;
import reactor.core.publisher.Mono;

import java.time.Clock;

/**
 * Accepts a purchase (or complimentary) request, persists the order as {@code PROCESSING} and enqueues it. The
 * inventory is not touched here: the order consumer does it, so the API answers fast even under heavy load.
 * <p>
 * The operation is idempotent: the order id is derived from the customer and its {@code Idempotency-Key}, so a
 * retried request returns the original order (re-publishing its command if it is still being processed).
 */
public class PlaceOrderUseCase {

    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final OrderCommandPublisher commandPublisher;
    private final ReservationPolicy policy;
    private final Clock clock;

    public PlaceOrderUseCase(EventRepository eventRepository, OrderRepository orderRepository,
                             OrderCommandPublisher commandPublisher, ReservationPolicy policy, Clock clock) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
        this.commandPublisher = commandPublisher;
        this.policy = policy;
        this.clock = clock;
    }

    public Mono<Order> place(OrderPlacement request) {
        return Mono.fromRunnable(() -> request.validate(policy))
                .then(Mono.defer(() -> orderRepository.findById(request.orderId())))
                .flatMap(existing -> replay(existing, request))
                .switchIfEmpty(Mono.defer(() -> submit(request)));
    }

    private Mono<Order> submit(OrderPlacement request) {
        return eventRepository.findById(request.eventId())
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessError.EVENT_NOT_FOUND)))
                .map(event -> newOrder(event, request))
                .flatMap(orderRepository::create)
                .flatMap(this::enqueue)
                .onErrorResume(error -> RetryPolicies.isBusiness(error, BusinessError.ORDER_ALREADY_EXISTS),
                        error -> orderRepository.findById(request.orderId())
                                .flatMap(existing -> replay(existing, request)));
    }

    /**
     * Fail-fast check against the current inventory. It is only an optimization to reject obvious sold-outs: the
     * authoritative check is the conditional write executed by the consumer.
     */
    private Order newOrder(Event event, OrderPlacement request) {
        var now = clock.instant();
        if (event.hasStarted(now)) {
            throw new BusinessException(BusinessError.EVENT_ALREADY_STARTED);
        }
        if (!event.inventory().hasAvailable(request.quantity())) {
            throw new BusinessException(BusinessError.INSUFFICIENT_INVENTORY);
        }
        return Order.request(request, now);
    }

    private Mono<Order> replay(Order existing, OrderPlacement request) {
        if (!existing.matches(request)) {
            return Mono.error(new BusinessException(BusinessError.IDEMPOTENCY_KEY_REUSED));
        }
        // The command may have been lost between persisting the order and publishing it: publish it again.
        // The consumer is idempotent, so a duplicate is harmless.
        return existing.status() == OrderStatus.PROCESSING ? enqueue(existing) : Mono.just(existing);
    }

    private Mono<Order> enqueue(Order order) {
        return commandPublisher.publish(OrderCommand.initialFor(order))
                .retryWhen(RetryPolicies.transientFailures())
                .thenReturn(order);
    }
}
