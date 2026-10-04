package co.com.ticketing.usecase.order;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderCommand;
import co.com.ticketing.model.order.gateways.OrderCommandPublisher;
import co.com.ticketing.model.order.gateways.OrderTransitionGateway;
import co.com.ticketing.usecase.support.RetryPolicies;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.regex.Pattern;

/**
 * The customer confirms a reserved order: the tickets move to {@code PENDING_CONFIRMATION} (so the expiration
 * process no longer releases them) and the payment is processed asynchronously by the order consumer.
 */
public class ConfirmOrderUseCase {

    private static final Pattern PAYMENT_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{4,128}$");

    private final GetOrderUseCase getOrderUseCase;
    private final OrderTransitionGateway transitionGateway;
    private final OrderCommandPublisher commandPublisher;
    private final Clock clock;

    public ConfirmOrderUseCase(GetOrderUseCase getOrderUseCase, OrderTransitionGateway transitionGateway,
                               OrderCommandPublisher commandPublisher, Clock clock) {
        this.getOrderUseCase = getOrderUseCase;
        this.transitionGateway = transitionGateway;
        this.commandPublisher = commandPublisher;
        this.clock = clock;
    }

    public Mono<Order> confirm(String orderId, String customerId, String paymentToken) {
        if (paymentToken == null || !PAYMENT_TOKEN.matcher(paymentToken).matches()) {
            return Mono.error(new BusinessException(BusinessError.INVALID_REQUEST, "paymentToken is not valid"));
        }
        return Mono.defer(() -> getOrderUseCase.get(orderId, customerId))
                .flatMap(order -> switch (order.status()) {
                    case RESERVED -> transitionGateway.apply(order.requestConfirmation(clock.instant()))
                            .flatMap(confirmed -> requestPayment(confirmed, paymentToken));
                    // Replayed request: the payment command might not have been published, publishing it again
                    // is safe because the consumer and the payment provider are idempotent by order id.
                    case PENDING_CONFIRMATION -> requestPayment(order, paymentToken);
                    case SOLD -> Mono.just(order);
                    case RELEASED -> Mono.error(new BusinessException(BusinessError.RESERVATION_EXPIRED));
                    default -> Mono.error(new BusinessException(BusinessError.INVALID_STATE_TRANSITION,
                            "Order in status %s cannot be confirmed".formatted(order.status())));
                })
                .retryWhen(RetryPolicies.transientFailuresOrStaleOrder());
    }

    private Mono<Order> requestPayment(Order order, String paymentToken) {
        return commandPublisher.publish(OrderCommand.processPayment(order.id(), paymentToken))
                .retryWhen(RetryPolicies.transientFailures())
                .thenReturn(order);
    }
}
