package co.com.ticketing.model.order.gateways;

import co.com.ticketing.model.order.OrderCommand;
import reactor.core.publisher.Mono;

public interface OrderCommandPublisher {

    Mono<Void> publish(OrderCommand command);
}
