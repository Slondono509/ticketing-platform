package co.com.ticketing.usecase.order;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderCommand;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.ReservationPolicy;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.order.gateways.OrderTransitionGateway;
import co.com.ticketing.model.payment.PaymentCharge;
import co.com.ticketing.model.payment.PaymentResult;
import co.com.ticketing.model.payment.gateways.PaymentGateway;
import co.com.ticketing.usecase.support.RetryPolicies;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.function.Function;

/**
 * Executes the commands consumed from the order queue. Messages are delivered at least once, so every handler is
 * idempotent: it re-reads the order and only acts when the order is in the state the command expects. Any other
 * state means the command was already applied by a previous delivery and the message is simply acknowledged.
 */
public class ProcessOrderCommandUseCase {

    private static final System.Logger LOG = System.getLogger(ProcessOrderCommandUseCase.class.getName());

    private final OrderRepository orderRepository;
    private final OrderTransitionGateway transitionGateway;
    private final PaymentGateway paymentGateway;
    private final ReservationPolicy policy;
    private final Clock clock;

    public ProcessOrderCommandUseCase(OrderRepository orderRepository, OrderTransitionGateway transitionGateway,
                                      PaymentGateway paymentGateway, ReservationPolicy policy, Clock clock) {
        this.orderRepository = orderRepository;
        this.transitionGateway = transitionGateway;
        this.paymentGateway = paymentGateway;
        this.policy = policy;
        this.clock = clock;
    }

    public Mono<Order> process(OrderCommand command) {
        return Mono.defer(() -> orderRepository.findById(command.orderId()))
                .switchIfEmpty(Mono.fromRunnable(() ->
                        LOG.log(System.Logger.Level.WARNING, "Ignoring command for unknown order {0}", command.orderId())))
                .flatMap(order -> switch (command.action()) {
                    case RESERVE -> whenStatus(order, OrderStatus.PROCESSING, this::reserve);
                    case GRANT_COMPLIMENTARY -> whenStatus(order, OrderStatus.PROCESSING, this::grantComplimentary);
                    case PROCESS_PAYMENT -> whenStatus(order, OrderStatus.PENDING_CONFIRMATION,
                            pending -> charge(pending, command.paymentToken()));
                })
                // A stale read (another consumer won the race) re-runs the command against the fresh order.
                .retryWhen(RetryPolicies.transientFailuresOrStaleOrder());
    }

    private Mono<Order> reserve(Order order) {
        return applyOrReject(order, order.reserve(clock.instant(), policy.reservationTtl()));
    }

    private Mono<Order> grantComplimentary(Order order) {
        return applyOrReject(order, order.grantComplimentary(clock.instant()));
    }

    /** The conditional write is the source of truth: if the inventory ran out the order is rejected. */
    private Mono<Order> applyOrReject(Order order, OrderTransition transition) {
        return transitionGateway.apply(transition)
                .onErrorResume(error -> RetryPolicies.isBusiness(error, BusinessError.INSUFFICIENT_INVENTORY),
                        error -> transitionGateway.apply(order.reject(clock.instant(), Order.REASON_SOLD_OUT)));
    }

    private Mono<Order> charge(Order order, String paymentToken) {
        var request = new PaymentCharge(order.id(), order.customerId(), order.quantity(), paymentToken);
        return paymentGateway.charge(request)
                .retryWhen(RetryPolicies.transientFailures())
                .flatMap(result -> switch (result) {
                    case PaymentResult.Approved approved -> transitionGateway.apply(order.completeSale(clock.instant()));
                    case PaymentResult.Declined declined ->
                            transitionGateway.apply(order.release(clock.instant(), Order.REASON_PAYMENT_DECLINED));
                });
    }

    private static Mono<Order> whenStatus(Order order, OrderStatus expected,
                                          Function<Order, Mono<Order>> action) {
        if (order.status() != expected) {
            LOG.log(System.Logger.Level.INFO, "Order {0} is {1}, command already applied", order.id(), order.status());
            return Mono.just(order);
        }
        return action.apply(order);
    }
}
