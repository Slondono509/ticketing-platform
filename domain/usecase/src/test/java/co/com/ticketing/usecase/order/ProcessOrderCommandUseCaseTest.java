package co.com.ticketing.usecase.order;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.common.ex.TechnicalError;
import co.com.ticketing.model.common.ex.TechnicalException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderCommand;
import co.com.ticketing.model.order.OrderPlacement;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.OrderType;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.order.gateways.OrderTransitionGateway;
import co.com.ticketing.model.payment.PaymentResult;
import co.com.ticketing.model.payment.gateways.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static co.com.ticketing.usecase.Fixtures.CLOCK;
import static co.com.ticketing.usecase.Fixtures.EVENT_ID;
import static co.com.ticketing.usecase.Fixtures.NOW;
import static co.com.ticketing.usecase.Fixtures.POLICY;
import static co.com.ticketing.usecase.Fixtures.pending;
import static co.com.ticketing.usecase.Fixtures.processing;
import static co.com.ticketing.usecase.Fixtures.reserved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessOrderCommandUseCaseTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderTransitionGateway transitionGateway;
    @Mock
    private PaymentGateway paymentGateway;

    private ProcessOrderCommandUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ProcessOrderCommandUseCase(orderRepository, transitionGateway, paymentGateway, POLICY, CLOCK);
    }

    private void applyTransitionsSuccessfully() {
        when(transitionGateway.apply(any())).thenAnswer(invocation ->
                Mono.just(invocation.<OrderTransition>getArgument(0).next()));
    }

    @Test
    void reserveCommandReservesTheTickets() {
        var order = processing(3);
        when(orderRepository.findById(order.id())).thenReturn(Mono.just(order));
        applyTransitionsSuccessfully();

        StepVerifier.create(useCase.process(OrderCommand.reserve(order.id())))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo(OrderStatus.RESERVED);
                    assertThat(result.expiresAt()).isEqualTo(NOW.plus(POLICY.reservationTtl()));
                })
                .verifyComplete();
    }

    @Test
    void insufficientInventoryRejectsTheOrder() {
        var order = processing(3);
        var transitions = ArgumentCaptor.forClass(OrderTransition.class);
        when(orderRepository.findById(order.id())).thenReturn(Mono.just(order));
        when(transitionGateway.apply(transitions.capture()))
                .thenReturn(Mono.error(new BusinessException(BusinessError.INSUFFICIENT_INVENTORY)))
                .thenAnswer(invocation -> Mono.just(invocation.<OrderTransition>getArgument(0).next()));

        StepVerifier.create(useCase.process(OrderCommand.reserve(order.id())))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo(OrderStatus.REJECTED);
                    assertThat(result.statusReason()).isEqualTo(Order.REASON_SOLD_OUT);
                })
                .verifyComplete();
        assertThat(transitions.getAllValues()).extracting(t -> t.next().status())
                .containsExactly(OrderStatus.RESERVED, OrderStatus.REJECTED);
    }

    @Test
    void duplicatedDeliveryIsAcknowledgedWithoutSideEffects() {
        var order = reserved(1);
        when(orderRepository.findById(order.id())).thenReturn(Mono.just(order));

        StepVerifier.create(useCase.process(OrderCommand.reserve(order.id()))).expectNext(order).verifyComplete();
        verifyNoInteractions(transitionGateway, paymentGateway);
    }

    @Test
    void unknownOrderCompletesEmpty() {
        when(orderRepository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.process(OrderCommand.reserve("missing"))).verifyComplete();
    }

    @Test
    void complimentaryCommandGrantsTickets() {
        var order = Order.request(new OrderPlacement("guest", EVENT_ID, 2, "key", OrderType.COMPLIMENTARY), NOW);
        when(orderRepository.findById(order.id())).thenReturn(Mono.just(order));
        applyTransitionsSuccessfully();

        StepVerifier.create(useCase.process(OrderCommand.grantComplimentary(order.id())))
                .expectNextMatches(result -> result.status() == OrderStatus.COMPLIMENTARY)
                .verifyComplete();
    }

    @Test
    void approvedPaymentCompletesTheSale() {
        var order = pending(2);
        when(orderRepository.findById(order.id())).thenReturn(Mono.just(order));
        when(paymentGateway.charge(any())).thenReturn(Mono.just(new PaymentResult.Approved("ref-1")));
        applyTransitionsSuccessfully();

        StepVerifier.create(useCase.process(OrderCommand.processPayment(order.id(), "tok_ok")))
                .expectNextMatches(result -> result.status() == OrderStatus.SOLD)
                .verifyComplete();
    }

    @Test
    void declinedPaymentReleasesTheTickets() {
        var order = pending(2);
        when(orderRepository.findById(order.id())).thenReturn(Mono.just(order));
        when(paymentGateway.charge(any())).thenReturn(Mono.just(new PaymentResult.Declined("NO_FUNDS")));
        applyTransitionsSuccessfully();

        StepVerifier.create(useCase.process(OrderCommand.processPayment(order.id(), "tok_declined")))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo(OrderStatus.RELEASED);
                    assertThat(result.statusReason()).isEqualTo(Order.REASON_PAYMENT_DECLINED);
                })
                .verifyComplete();
    }

    @Test
    void paymentProviderOutageIsRetriedAndThenPropagated() {
        var order = pending(1);
        when(orderRepository.findById(order.id())).thenReturn(Mono.just(order));
        when(paymentGateway.charge(any())).thenReturn(Mono.error(
                new TechnicalException(TechnicalError.PAYMENT_PROVIDER_FAILURE, new RuntimeException())));

        StepVerifier.withVirtualTime(() -> useCase.process(OrderCommand.processPayment(order.id(), "tok")))
                .thenAwait(Duration.ofMinutes(1))
                .expectErrorMatches(TechnicalException::isRetryable)
                .verify();
    }

    @Test
    void staleReadIsRetriedAgainstTheFreshOrder() {
        var order = processing(1);
        var reservedByOtherConsumer = order.reserve(NOW, POLICY.reservationTtl()).next();
        when(orderRepository.findById(order.id())).thenReturn(Mono.just(order), Mono.just(reservedByOtherConsumer));
        when(transitionGateway.apply(any()))
                .thenReturn(Mono.error(new BusinessException(BusinessError.ORDER_STATE_CHANGED)));

        StepVerifier.withVirtualTime(() -> useCase.process(OrderCommand.reserve(order.id())))
                .thenAwait(Duration.ofSeconds(2))
                .expectNext(reservedByOtherConsumer)
                .verifyComplete();
        verify(transitionGateway, times(1)).apply(any());
    }
}
