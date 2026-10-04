package co.com.ticketing.api.handler;

import co.com.ticketing.api.dto.AvailabilityResponse;
import co.com.ticketing.api.dto.ComplimentaryTicketsRequest;
import co.com.ticketing.api.dto.CreateEventRequest;
import co.com.ticketing.api.dto.EventResponse;
import co.com.ticketing.api.dto.OrderResponse;
import co.com.ticketing.model.order.OrderPlacement;
import co.com.ticketing.model.order.OrderType;
import co.com.ticketing.usecase.event.CreateEventUseCase;
import co.com.ticketing.usecase.event.QueryEventsUseCase;
import co.com.ticketing.usecase.order.PlaceOrderUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;

@Component
public class EventHandler {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final CreateEventUseCase createEventUseCase;
    private final QueryEventsUseCase queryEventsUseCase;
    private final PlaceOrderUseCase placeOrderUseCase;
    private final Duration streamInterval;
    private final Duration streamMaxDuration;

    public EventHandler(CreateEventUseCase createEventUseCase, QueryEventsUseCase queryEventsUseCase,
                        PlaceOrderUseCase placeOrderUseCase,
                        @Value("${api.availability-stream.interval:1s}") Duration streamInterval,
                        @Value("${api.availability-stream.max-duration:10m}") Duration streamMaxDuration) {
        this.createEventUseCase = createEventUseCase;
        this.queryEventsUseCase = queryEventsUseCase;
        this.placeOrderUseCase = placeOrderUseCase;
        this.streamInterval = streamInterval;
        this.streamMaxDuration = streamMaxDuration;
    }

    public Mono<ServerResponse> create(ServerRequest request) {
        return RequestReader.body(request, CreateEventRequest.class)
                .flatMap(body -> createEventUseCase.create(body.toModel()))
                .flatMap(event -> ServerResponse.created(URI.create("/api/v1/events/" + event.id()))
                        .bodyValue(EventResponse.from(event)));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return Mono.fromCallable(() -> RequestReader.pathId(request, "eventId"))
                .flatMap(queryEventsUseCase::getById)
                .flatMap(event -> ServerResponse.ok().bodyValue(EventResponse.from(event)));
    }

    public Mono<ServerResponse> listUpcoming(ServerRequest request) {
        var limit = request.queryParam("limit").map(EventHandler::parseLimit).orElse(DEFAULT_PAGE_SIZE);
        return ServerResponse.ok()
                .body(queryEventsUseCase.listUpcoming(limit).map(EventResponse::from), EventResponse.class);
    }

    public Mono<ServerResponse> availability(ServerRequest request) {
        return Mono.fromCallable(() -> RequestReader.pathId(request, "eventId"))
                .flatMap(queryEventsUseCase::getAvailability)
                .flatMap(availability -> ServerResponse.ok().bodyValue(AvailabilityResponse.from(availability)));
    }

    /**
     * Server-Sent Events stream that pushes the availability whenever it changes. The event is validated first so
     * an unknown id gets a proper 404 instead of a broken stream; the stream is bounded to limit idle connections.
     */
    public Mono<ServerResponse> availabilityStream(ServerRequest request) {
        return Mono.fromCallable(() -> RequestReader.pathId(request, "eventId"))
                .flatMap(eventId -> queryEventsUseCase.getById(eventId).thenReturn(eventId))
                .flatMap(eventId -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(queryEventsUseCase.watchAvailability(eventId, streamInterval)
                                .take(streamMaxDuration)
                                .map(AvailabilityResponse::from), AvailabilityResponse.class));
    }

    public Mono<ServerResponse> issueComplimentary(ServerRequest request) {
        return Mono.fromCallable(() -> RequestReader.pathId(request, "eventId"))
                .zipWith(RequestReader.body(request, ComplimentaryTicketsRequest.class))
                .map(tuple -> new OrderPlacement(tuple.getT2().recipientId(), tuple.getT1(),
                        RequestReader.intOrZero(tuple.getT2().quantity()),
                        RequestReader.header(request, RequestReader.IDEMPOTENCY_KEY_HEADER), OrderType.COMPLIMENTARY))
                .flatMap(placeOrderUseCase::place)
                .flatMap(order -> ServerResponse.accepted()
                        .location(URI.create("/api/v1/orders/" + order.id()))
                        .bodyValue(OrderResponse.from(order)));
    }

    private static int parseLimit(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return DEFAULT_PAGE_SIZE;
        }
    }
}
