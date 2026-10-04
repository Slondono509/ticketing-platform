package co.com.ticketing.usecase.order;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderCommand;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.gateways.OrderCommandPublisher;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.order.gateways.OrderTransitionGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import static co.com.ticketing.usecase.Fixtures.CLOCK;
import static co.com.ticketing.usecase.Fixtures.CUSTOMER_ID;
import static co.com.ticketing.usecase.Fixtures.NOW;
import static co.com.ticketing.usecase.Fixtures.businessError;
import static co.com.ticketing.usecase.Fixtures.pending;
import static co.com.ticketing.usecase.Fixtures.processing;
import static co.com.ticketing.usecase.Fixtures.reserved;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmAndGetOrderUseCaseTest {

    private static final String TOKEN = "tok_visa_ok";

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderTransitionGateway transitionGateway;
    @Mock
    private OrderCommandPublisher commandPublisher;

    private GetOrderUseCase getOrderUseCase;
    private ConfirmOrderUseCase confirmOrderUseCase;

    @BeforeEach
    void setUp() {
        getOrderUseCase = new GetOrderUseCase(orderRepository);
        confirmOrderUseCase = new ConfirmOrderUseCase(getOrderUseCase, transitionGateway, commandPublisher, CLOCK);
    }

    @Test
    void ownerCanReadTheOrder() {
        var order = reserved(1);
        when(orderRepository.findById(order.id())).thenReturn(Mono.just(order));

        StepVerifier.create(getOrderUseCase.get(order.id(), CUSTOMER_ID)).expectNext(order).verifyComplete();
    }

    @Test
    void ordersOfOtherCustomersLookNotFound() {
        var order = reserved(1);
        when(orderRepository.findById(order.id())).thenReturn(Mono.just(order));

        StepVerifier.create(getOrderUseCase.get(order.id(), "intruder"))
                .expectErrorMatches(businessError(BusinessError.ORDER_NOT_FOUND))
                .verify();
    }

    @Test
    void confirmMovesTheReservationToPendingAndRequestsThePayment() {
        var order = reserved(2);
        when(orderRepository.findById(order.id())).thenReturn(Mono.just(order));
        when(transitionGateway.apply(any())).thenAnswer(invocation ->
                Mono.just(invocation.<OrderTransition>getArgument(0).next()));
        when(commandPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(confirmOrderUseCase.confirm(order.id(), CUSTOMER_ID, TOKEN))
                .expectNextMatches(confirmed -> confirmed.status() == OrderStatus.PENDING_CONFIRMATION)
                .verifyComplete();
        verify(commandPublisher).publish(OrderCommand.processPayment(order.id(), TOKEN));
    }

    @Test
    void replayedConfirmationRepublishesThePaymentCommand() {
        var order = pending(1);
        when(orderRepository.findById(order.id())).thenReturn(Mono.just(order));
        when(commandPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(confirmOrderUseCase.confirm(order.id(), CUSTOMER_ID, TOKEN)).expectNext(order).verifyComplete();
        verify(transitionGateway, never()).apply(any());
    }

    @Test
    void soldOrderIsReturnedAsIs() {
        var sold = pending(1).completeSale(NOW).next();
        when(orderRepository.findById(sold.id())).thenReturn(Mono.just(sold));

        StepVerifier.create(confirmOrderUseCase.confirm(sold.id(), CUSTOMER_ID, TOKEN)).expectNext(sold).verifyComplete();
        verifyNoInteractions(commandPublisher);
    }

    @Test
    void releasedOrderCannotBeConfirmed() {
        var released = reserved(1).release(NOW, Order.REASON_RESERVATION_EXPIRED).next();
        when(orderRepository.findById(released.id())).thenReturn(Mono.just(released));

        StepVerifier.create(confirmOrderUseCase.confirm(released.id(), CUSTOMER_ID, TOKEN))
                .expectErrorMatches(businessError(BusinessError.RESERVATION_EXPIRED))
                .verify();
    }

    @Test
    void processingOrderCannotBeConfirmedYet() {
        var order = processing(1);
        when(orderRepository.findById(order.id())).thenReturn(Mono.just(order));

        StepVerifier.create(confirmOrderUseCase.confirm(order.id(), CUSTOMER_ID, TOKEN))
                .expectErrorMatches(businessError(BusinessError.INVALID_STATE_TRANSITION))
                .verify();
    }

    @Test
    void expiredReservationCannotBeConfirmed() {
        var order = reserved(1);
        var later = Clock.fixed(NOW.plus(Duration.ofMinutes(11)), ZoneOffset.UTC);
        var useCase = new ConfirmOrderUseCase(getOrderUseCase, transitionGateway, commandPublisher, later);
        when(orderRepository.findById(order.id())).thenReturn(Mono.just(order));

        StepVerifier.create(useCase.confirm(order.id(), CUSTOMER_ID, TOKEN))
                .expectErrorMatches(businessError(BusinessError.RESERVATION_EXPIRED))
                .verify();
    }

    @Test
    void concurrentModificationIsReEvaluatedAgainstTheFreshOrder() {
        var order = reserved(1);
        var alreadyPending = order.requestConfirmation(NOW).next();
        when(orderRepository.findById(order.id())).thenReturn(Mono.just(order), Mono.just(alreadyPending));
        when(transitionGateway.apply(any()))
                .thenReturn(Mono.error(new BusinessException(BusinessError.ORDER_STATE_CHANGED)));
        when(commandPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.withVirtualTime(() -> confirmOrderUseCase.confirm(order.id(), CUSTOMER_ID, TOKEN))
                .thenAwait(Duration.ofSeconds(2))
                .expectNext(alreadyPending)
                .verifyComplete();
    }

    @Test
    void invalidPaymentTokenIsRejected() {
        StepVerifier.create(confirmOrderUseCase.confirm("o-1", CUSTOMER_ID, "bad token!"))
                .expectErrorMatches(businessError(BusinessError.INVALID_REQUEST))
                .verify();
        StepVerifier.create(confirmOrderUseCase.confirm("o-1", CUSTOMER_ID, null))
                .expectErrorMatches(businessError(BusinessError.INVALID_REQUEST))
                .verify();
        verifyNoInteractions(orderRepository);
    }
}
