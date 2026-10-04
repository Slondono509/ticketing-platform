package co.com.ticketing.usecase.order;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.common.ex.TechnicalError;
import co.com.ticketing.model.common.ex.TechnicalException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderPlacement;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.OrderType;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.order.gateways.OrderTransitionGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static co.com.ticketing.usecase.Fixtures.CLOCK;
import static co.com.ticketing.usecase.Fixtures.EVENT_ID;
import static co.com.ticketing.usecase.Fixtures.NOW;
import static co.com.ticketing.usecase.Fixtures.POLICY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReleaseExpiredReservationsUseCaseTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderTransitionGateway transitionGateway;

    private ReleaseExpiredReservationsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ReleaseExpiredReservationsUseCase(orderRepository, transitionGateway, CLOCK);
    }

    private static Order expiredReservation(String customer) {
        return Order.request(
                        new OrderPlacement(customer, EVENT_ID, 1, "key", OrderType.PURCHASE), NOW.minusSeconds(900))
                .reserve(NOW.minusSeconds(900), POLICY.reservationTtl()).next();
    }

    @Test
    void releasesEveryExpiredReservationAndSkipsTheOnesThatChanged() {
        var expired = expiredReservation("c-1");
        var confirmedMeanwhile = expiredReservation("c-2");
        var transient1 = expiredReservation("c-3");
        when(orderRepository.findExpiredReservations(NOW, ReleaseExpiredReservationsUseCase.BATCH_SIZE))
                .thenReturn(Flux.just(expired, confirmedMeanwhile, transient1));
        when(transitionGateway.apply(argThat(t -> t != null && t.previous().equals(expired))))
                .thenAnswer(invocation -> Mono.just(invocation.<OrderTransition>getArgument(0).next()));
        when(transitionGateway.apply(argThat(t -> t != null && t.previous().equals(confirmedMeanwhile))))
                .thenReturn(Mono.error(new BusinessException(BusinessError.ORDER_STATE_CHANGED)));
        when(transitionGateway.apply(argThat(t -> t != null && t.previous().equals(transient1))))
                .thenReturn(Mono.error(new TechnicalException(TechnicalError.CONCURRENT_UPDATE, null)),
                        Mono.just(transient1.release(NOW, "x").next()));

        StepVerifier.withVirtualTime(() -> useCase.releaseExpired())
                .thenAwait(Duration.ofSeconds(5))
                .expectNext(2L)
                .verifyComplete();
    }

    @Test
    void releasedOrdersReturnTicketsToAvailable() {
        var expired = expiredReservation("c-1");
        when(orderRepository.findExpiredReservations(eq(NOW), any(Integer.class))).thenReturn(Flux.just(expired));
        when(transitionGateway.apply(any())).thenAnswer(invocation -> {
            OrderTransition transition = invocation.getArgument(0);
            return transition.next().status() == OrderStatus.RELEASED
                    && transition.inventoryChange().isPresent()
                    ? Mono.just(transition.next())
                    : Mono.error(new IllegalStateException("unexpected transition"));
        });

        StepVerifier.create(useCase.releaseExpired()).expectNext(1L).verifyComplete();
    }

    @Test
    void nothingToRelease() {
        when(orderRepository.findExpiredReservations(any(), any(Integer.class))).thenReturn(Flux.empty());

        StepVerifier.create(useCase.releaseExpired()).expectNext(0L).verifyComplete();
    }
}
