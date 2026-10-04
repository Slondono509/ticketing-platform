package co.com.ticketing.api.handler;

import co.com.ticketing.api.dto.ConfirmOrderRequest;
import co.com.ticketing.api.dto.OrderResponse;
import co.com.ticketing.api.dto.PlaceOrderRequest;
import co.com.ticketing.model.order.OrderPlacement;
import co.com.ticketing.model.order.OrderType;
import co.com.ticketing.usecase.order.ConfirmOrderUseCase;
import co.com.ticketing.usecase.order.GetOrderUseCase;
import co.com.ticketing.usecase.order.PlaceOrderUseCase;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;

@Component
public class OrderHandler {

    private final PlaceOrderUseCase placeOrderUseCase;
    private final GetOrderUseCase getOrderUseCase;
    private final ConfirmOrderUseCase confirmOrderUseCase;

    public OrderHandler(PlaceOrderUseCase placeOrderUseCase, GetOrderUseCase getOrderUseCase,
                        ConfirmOrderUseCase confirmOrderUseCase) {
        this.placeOrderUseCase = placeOrderUseCase;
        this.getOrderUseCase = getOrderUseCase;
        this.confirmOrderUseCase = confirmOrderUseCase;
    }

    /** 202 Accepted: the order is queued, its final status is obtained with GET /orders/{orderId}. */
    public Mono<ServerResponse> place(ServerRequest request) {
        return RequestReader.body(request, PlaceOrderRequest.class)
                .map(body -> new OrderPlacement(
                        RequestReader.header(request, RequestReader.CUSTOMER_ID_HEADER),
                        body.eventId(),
                        RequestReader.intOrZero(body.quantity()),
                        RequestReader.header(request, RequestReader.IDEMPOTENCY_KEY_HEADER),
                        OrderType.PURCHASE))
                .flatMap(placeOrderUseCase::place)
                .flatMap(order -> ServerResponse.accepted()
                        .location(URI.create("/api/v1/orders/" + order.id()))
                        .bodyValue(OrderResponse.from(order)));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return Mono.fromCallable(() -> RequestReader.pathId(request, "orderId"))
                .flatMap(orderId -> getOrderUseCase.get(orderId,
                        RequestReader.header(request, RequestReader.CUSTOMER_ID_HEADER)))
                .flatMap(order -> ServerResponse.ok().bodyValue(OrderResponse.from(order)));
    }

    /** 202 Accepted: the payment is processed asynchronously and the order ends SOLD or RELEASED. */
    public Mono<ServerResponse> confirm(ServerRequest request) {
        return Mono.fromCallable(() -> RequestReader.pathId(request, "orderId"))
                .zipWith(RequestReader.body(request, ConfirmOrderRequest.class))
                .flatMap(tuple -> confirmOrderUseCase.confirm(tuple.getT1(),
                        RequestReader.header(request, RequestReader.CUSTOMER_ID_HEADER),
                        tuple.getT2().paymentToken()))
                .flatMap(order -> ServerResponse.accepted().bodyValue(OrderResponse.from(order)));
    }
}
