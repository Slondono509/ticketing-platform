package co.com.ticketing.api;

import co.com.ticketing.api.dto.AvailabilityResponse;
import co.com.ticketing.api.error.ApiErrorMapper;
import co.com.ticketing.api.handler.EventHandler;
import co.com.ticketing.api.handler.OrderHandler;
import co.com.ticketing.api.security.AdminApiKeyFilter;
import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.common.ex.TechnicalError;
import co.com.ticketing.model.common.ex.TechnicalException;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.EventAvailability;
import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.event.NewEvent;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderPlacement;
import co.com.ticketing.model.order.OrderType;
import co.com.ticketing.usecase.event.CreateEventUseCase;
import co.com.ticketing.usecase.event.QueryEventsUseCase;
import co.com.ticketing.usecase.order.ConfirmOrderUseCase;
import co.com.ticketing.usecase.order.GetOrderUseCase;
import co.com.ticketing.usecase.order.PlaceOrderUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouterRestTest {

    private static final String API_KEY = "test-admin-key";
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final Event EVENT = Event.create("e-1",
            new NewEvent("Rock Fest", "Arena", NOW.plus(Duration.ofDays(10)), 100), NOW);

    @Mock
    private CreateEventUseCase createEventUseCase;
    @Mock
    private QueryEventsUseCase queryEventsUseCase;
    @Mock
    private PlaceOrderUseCase placeOrderUseCase;
    @Mock
    private GetOrderUseCase getOrderUseCase;
    @Mock
    private ConfirmOrderUseCase confirmOrderUseCase;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        var eventHandler = new EventHandler(createEventUseCase, queryEventsUseCase, placeOrderUseCase,
                Duration.ofMillis(10), Duration.ofSeconds(5));
        var orderHandler = new OrderHandler(placeOrderUseCase, getOrderUseCase, confirmOrderUseCase);
        var router = new RouterRest().routerFunction(eventHandler, orderHandler, new AdminApiKeyFilter(API_KEY),
                new ApiErrorMapper());
        client = WebTestClient.bindToRouterFunction(router).build();
    }

    private static Order order(OrderType type) {
        return Order.request(new OrderPlacement("customer-1", "e-1", 2, "key-1", type), NOW);
    }

    @Test
    void createEventRequiresTheApiKey() {
        client.post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Rock")).exchange()
                .expectStatus().isUnauthorized();
        client.post().uri("/api/v1/events").header(AdminApiKeyFilter.API_KEY_HEADER, "wrong")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "Rock")).exchange()
                .expectStatus().isUnauthorized();
        verifyNoInteractions(createEventUseCase);
    }

    @Test
    void createEvent() {
        var captor = ArgumentCaptor.forClass(NewEvent.class);
        when(createEventUseCase.create(captor.capture())).thenReturn(Mono.just(EVENT));

        client.post().uri("/api/v1/events").header(AdminApiKeyFilter.API_KEY_HEADER, API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Rock Fest", "venue", "Arena", "startsAt", "2026-10-11T10:00:00Z",
                        "totalCapacity", 100))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().location("/api/v1/events/e-1")
                .expectBody()
                .jsonPath("$.id").isEqualTo("e-1")
                .jsonPath("$.availableTickets").isEqualTo(100);
        assertThat(captor.getValue().totalCapacity()).isEqualTo(100);
    }

    @Test
    void emptyOrMalformedBodiesAreBadRequests() {
        client.post().uri("/api/v1/events").header(AdminApiKeyFilter.API_KEY_HEADER, API_KEY)
                .contentType(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.title").isEqualTo("INVALID_REQUEST");
        client.post().uri("/api/v1/events").header(AdminApiKeyFilter.API_KEY_HEADER, API_KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{not json").exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void getAndListEvents() {
        when(queryEventsUseCase.getById("e-1")).thenReturn(Mono.just(EVENT));
        when(queryEventsUseCase.listUpcoming(anyInt())).thenReturn(Flux.just(EVENT));

        client.get().uri("/api/v1/events/e-1").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("Rock Fest");
        client.get().uri("/api/v1/events?limit=abc").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0].id").isEqualTo("e-1");
        client.get().uri("/api/v1/events?limit=5").exchange().expectStatus().isOk();
        verify(queryEventsUseCase).listUpcoming(5);
    }

    @Test
    void unknownEventIsNotFound() {
        when(queryEventsUseCase.getById("missing"))
                .thenReturn(Mono.error(new BusinessException(BusinessError.EVENT_NOT_FOUND)));

        client.get().uri("/api/v1/events/missing").exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.title").isEqualTo("EVENT_NOT_FOUND")
                .jsonPath("$.status").isEqualTo(404);
    }

    @Test
    void invalidPathIdentifiersAreRejected() {
        client.get().uri("/api/v1/events/" + "x".repeat(65)).exchange().expectStatus().isBadRequest();
        verifyNoInteractions(queryEventsUseCase);
    }

    @Test
    void availability() {
        var availability = new EventAvailability("e-1", 100, new Inventory(90, 6, 2, 1, 1), NOW);
        when(queryEventsUseCase.getAvailability("e-1")).thenReturn(Mono.just(availability));

        client.get().uri("/api/v1/events/e-1/availability").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.available").isEqualTo(90)
                .jsonPath("$.onHold").isEqualTo(8)
                .jsonPath("$.sold").isEqualTo(1)
                .jsonPath("$.complimentary").isEqualTo(1);
    }

    @Test
    void availabilityStreamUsesServerSentEvents() {
        var availability = new EventAvailability("e-1", 100, Inventory.of(100), NOW);
        when(queryEventsUseCase.getById("e-1")).thenReturn(Mono.just(EVENT));
        when(queryEventsUseCase.watchAvailability(eq("e-1"), any())).thenReturn(Flux.just(availability));

        var body = client.get().uri("/api/v1/events/e-1/availability/stream").accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(AvailabilityResponse.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(5));
        assertThat(body).extracting(AvailabilityResponse::available).containsExactly(100);
    }

    @Test
    void placeOrderIsAcceptedAsynchronously() {
        var captor = ArgumentCaptor.forClass(OrderPlacement.class);
        var order = order(OrderType.PURCHASE);
        when(placeOrderUseCase.place(captor.capture())).thenReturn(Mono.just(order));

        client.post().uri("/api/v1/orders")
                .header("X-Customer-Id", "customer-1").header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("eventId", "e-1", "quantity", 2))
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().location("/api/v1/orders/" + order.id())
                .expectBody()
                .jsonPath("$.status").isEqualTo("PROCESSING")
                .jsonPath("$.ticketStatus").doesNotExist();
        assertThat(captor.getValue()).isEqualTo(new OrderPlacement("customer-1", "e-1", 2, "key-1", OrderType.PURCHASE));
    }

    @Test
    void placeOrderRequiresIdentityAndIdempotencyKey() {
        client.post().uri("/api/v1/orders").header("X-Customer-Id", "customer-1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("eventId", "e-1", "quantity", 2))
                .exchange()
                .expectStatus().isBadRequest();
        client.post().uri("/api/v1/orders").header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("eventId", "e-1"))
                .exchange()
                .expectStatus().isBadRequest();
        verifyNoInteractions(placeOrderUseCase);
    }

    @Test
    void businessErrorsAreMappedToHttpStatuses() {
        when(placeOrderUseCase.place(any()))
                .thenReturn(Mono.error(new BusinessException(BusinessError.INSUFFICIENT_INVENTORY)))
                .thenReturn(Mono.error(new BusinessException(BusinessError.IDEMPOTENCY_KEY_REUSED)))
                .thenReturn(Mono.error(new TechnicalException(TechnicalError.MESSAGING_FAILURE, null)))
                .thenReturn(Mono.error(new TechnicalException(TechnicalError.INVALID_MESSAGE, null)));

        var statuses = new HttpStatus[]{HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_CONTENT,
                HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.INTERNAL_SERVER_ERROR};
        for (var status : statuses) {
            client.post().uri("/api/v1/orders")
                    .header("X-Customer-Id", "customer-1").header("Idempotency-Key", "key-1")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("eventId", "e-1", "quantity", 2))
                    .exchange()
                    .expectStatus().isEqualTo(status);
        }
    }

    @Test
    void retryableFailuresAdvertiseRetryAfter() {
        when(getOrderUseCase.get(anyString(), anyString()))
                .thenReturn(Mono.error(new TechnicalException(TechnicalError.PERSISTENCE_FAILURE, null)));

        client.get().uri("/api/v1/orders/o-1").header("X-Customer-Id", "customer-1").exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals("Retry-After", "1");
    }

    @Test
    void getOrder() {
        var order = order(OrderType.PURCHASE).reserve(NOW, Duration.ofMinutes(10)).next();
        when(getOrderUseCase.get(order.id(), "customer-1")).thenReturn(Mono.just(order));

        client.get().uri("/api/v1/orders/" + order.id()).header("X-Customer-Id", "customer-1").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RESERVED")
                .jsonPath("$.ticketStatus").isEqualTo("RESERVED")
                .jsonPath("$.tickets.length()").isEqualTo(2);
    }

    @Test
    void confirmOrder() {
        var pending = order(OrderType.PURCHASE).reserve(NOW, Duration.ofMinutes(10)).next()
                .requestConfirmation(NOW).next();
        when(confirmOrderUseCase.confirm(pending.id(), "customer-1", "tok_ok")).thenReturn(Mono.just(pending));

        client.post().uri("/api/v1/orders/" + pending.id() + "/confirm").header("X-Customer-Id", "customer-1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("paymentToken", "tok_ok"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() {
        when(getOrderUseCase.get(anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("secret internal detail")));

        client.get().uri("/api/v1/orders/o-1").header("X-Customer-Id", "customer-1").exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Unexpected error");
    }

    @Test
    void complimentaryTicketsAreIssuedByTheBackOffice() {
        var captor = ArgumentCaptor.forClass(OrderPlacement.class);
        when(placeOrderUseCase.place(captor.capture())).thenReturn(Mono.just(order(OrderType.COMPLIMENTARY)));

        client.post().uri("/api/v1/events/e-1/complimentary-tickets")
                .header(AdminApiKeyFilter.API_KEY_HEADER, API_KEY).header("Idempotency-Key", "gift-1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("recipientId", "guest-1", "quantity", 2))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.type").isEqualTo("COMPLIMENTARY");
        assertThat(captor.getValue()).isEqualTo(new OrderPlacement("guest-1", "e-1", 2, "gift-1", OrderType.COMPLIMENTARY));
    }

    @Test
    void missingApiKeyConfigurationFailsClosed() {
        var router = new RouterRest().routerFunction(
                new EventHandler(createEventUseCase, queryEventsUseCase, placeOrderUseCase, Duration.ofSeconds(1),
                        Duration.ofSeconds(1)),
                new OrderHandler(placeOrderUseCase, getOrderUseCase, confirmOrderUseCase),
                new AdminApiKeyFilter(""), new ApiErrorMapper());

        WebTestClient.bindToRouterFunction(router).build()
                .post().uri("/api/v1/events").header(AdminApiKeyFilter.API_KEY_HEADER, "")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of()).exchange()
                .expectStatus().isUnauthorized();
    }
}
