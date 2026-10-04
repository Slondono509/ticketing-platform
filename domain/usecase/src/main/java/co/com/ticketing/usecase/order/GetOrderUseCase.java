package co.com.ticketing.usecase.order;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.gateways.OrderRepository;
import reactor.core.publisher.Mono;

public class GetOrderUseCase {

    private final OrderRepository orderRepository;

    public GetOrderUseCase(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Orders of other customers are reported as not found, so the endpoint cannot be used to discover order ids.
     */
    public Mono<Order> get(String orderId, String customerId) {
        return orderRepository.findById(orderId)
                .filter(order -> order.isOwnedBy(customerId))
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessError.ORDER_NOT_FOUND)));
    }
}
