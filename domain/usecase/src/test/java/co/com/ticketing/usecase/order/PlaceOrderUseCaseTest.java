package co.com.ticketing.usecase.order;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.common.ex.TechnicalError;
import co.com.ticketing.model.common.ex.TechnicalException;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.order.OrderAction;
import co.com.ticketing.model.order.OrderCommand;
import co.com.ticketing.model.order.OrderPlacement;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderType;
import co.com.ticketing.model.order.gateways.OrderCommandPublisher;
import co.com.ticketing.model.order.gateways.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static co.com.ticketing.usecase.Fixtures.CLOCK;
import static co.com.ticketing.usecase.Fixtures.EVENT_ID;
import static co.com.ticketing.usecase.Fixtures.NOW;
import static co.com.ticketing.usecase.Fixtures.POLICY;
import static co.com.ticketing.usecase.Fixtures.businessError;
import static co.com.ticketing.usecase.Fixtures.event;
import static co.com.ticketing.usecase.Fixtures.placement;
import static co.com.ticketing.usecase.Fixtures.processing;
import static co.com.ticketing.usecase.Fixtures.reserved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaceOrderUseCaseTest {

    @Mock
    private EventRepository eventRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderCommandPublisher commandPublisher;

    private PlaceOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new PlaceOrderUseCase(eventRepository, orderRepository, commandPublisher, POLICY, CLOCK);
    }

    @Test
    void newOrderIsPersistedAsProcessingAndEnqueued() {
        var request = placement(2);
        when(orderRepository.findById(request.orderId())).thenReturn(Mono.empty());
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(event(10)));
        when(orderRepository.create(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(commandPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.place(request))
                .assertNext(order -> {
                    assertThat(order.id()).isEqualTo(request.orderId());
                    assertThat(order.status()).isEqualTo(OrderStatus.PROCESSING);
                })
                .verifyComplete();
        verify(commandPublisher).publish(new OrderCommand(request.orderId(), OrderAction.RESERVE, null));
    }

    @Test
    void complimentaryOrdersAreEnqueuedForGranting() {
        var request = new OrderPlacement("guest-1", EVENT_ID, 2, "key-1", OrderType.COMPLIMENTARY);
        when(orderRepository.findById(request.orderId())).thenReturn(Mono.empty());
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(event(10)));
        when(orderRepository.create(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(commandPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.place(request)).expectNextCount(1).verifyComplete();
        verify(commandPublisher).publish(OrderCommand.grantComplimentary(request.orderId()));
    }

    @Test
    void invalidRequestIsRejectedWithoutTouchingTheRepositories() {
        StepVerifier.create(useCase.place(placement(POLICY.maxTicketsPerOrder() + 1)))
                .expectErrorMatches(businessError(BusinessError.INVALID_REQUEST))
                .verify();
        verifyNoInteractions(orderRepository, eventRepository, commandPublisher);
    }

    @Test
    void replayOfAProcessedOrderReturnsItWithoutPublishing() {
        var existing = reserved(2);
        when(orderRepository.findById(existing.id())).thenReturn(Mono.just(existing));

        StepVerifier.create(useCase.place(placement(2))).expectNext(existing).verifyComplete();
        verifyNoInteractions(eventRepository, commandPublisher);
    }

    @Test
    void replayOfAProcessingOrderRepublishesItsCommand() {
        var existing = processing(2);
        when(orderRepository.findById(existing.id())).thenReturn(Mono.just(existing));
        when(commandPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.place(placement(2))).expectNext(existing).verifyComplete();
        verify(commandPublisher).publish(OrderCommand.reserve(existing.id()));
        verify(orderRepository, never()).create(any());
    }

    @Test
    void reusingAKeyWithADifferentPayloadIsRejected() {
        when(orderRepository.findById(placement(2).orderId())).thenReturn(Mono.just(processing(2)));

        StepVerifier.create(useCase.place(placement(3)))
                .expectErrorMatches(businessError(BusinessError.IDEMPOTENCY_KEY_REUSED))
                .verify();
    }

    @Test
    void unknownEventIsRejected() {
        when(orderRepository.findById(any())).thenReturn(Mono.empty());
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.place(placement(1)))
                .expectErrorMatches(businessError(BusinessError.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void startedEventIsRejected() {
        var started = new Event(EVENT_ID, "Rock", "Arena", NOW, 10, Inventory.of(10), NOW, 0);
        when(orderRepository.findById(any())).thenReturn(Mono.empty());
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(started));

        StepVerifier.create(useCase.place(placement(1)))
                .expectErrorMatches(businessError(BusinessError.EVENT_ALREADY_STARTED))
                .verify();
    }

    @Test
    void soldOutEventFailsFast() {
        when(orderRepository.findById(any())).thenReturn(Mono.empty());
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(event(1)));

        StepVerifier.create(useCase.place(placement(2)))
                .expectErrorMatches(businessError(BusinessError.INSUFFICIENT_INVENTORY))
                .verify();
        verify(orderRepository, never()).create(any());
    }

    @Test
    void concurrentDuplicateRequestResolvesToTheWinningOrder() {
        var winner = processing(2);
        when(orderRepository.findById(winner.id())).thenReturn(Mono.empty(), Mono.just(winner));
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(event(10)));
        when(orderRepository.create(any()))
                .thenReturn(Mono.error(new BusinessException(BusinessError.ORDER_ALREADY_EXISTS)));
        when(commandPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.place(placement(2))).expectNext(winner).verifyComplete();
    }

    @Test
    void transientPublishFailuresAreRetried() {
        var request = placement(1);
        var attempts = new AtomicInteger();
        when(orderRepository.findById(request.orderId())).thenReturn(Mono.empty());
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(event(10)));
        when(orderRepository.create(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(commandPublisher.publish(any())).thenReturn(Mono.defer(() -> attempts.incrementAndGet() < 3
                ? Mono.error(new TechnicalException(TechnicalError.MESSAGING_FAILURE, new RuntimeException()))
                : Mono.empty()));

        StepVerifier.withVirtualTime(() -> useCase.place(request))
                .thenAwait(Duration.ofSeconds(5))
                .expectNextCount(1)
                .verifyComplete();
        assertThat(attempts).hasValue(3);
    }
}
