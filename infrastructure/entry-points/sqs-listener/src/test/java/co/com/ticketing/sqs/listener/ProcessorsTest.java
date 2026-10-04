package co.com.ticketing.sqs.listener;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.common.ex.TechnicalError;
import co.com.ticketing.model.common.ex.TechnicalException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderCommand;
import co.com.ticketing.model.order.OrderPlacement;
import co.com.ticketing.model.order.OrderType;
import co.com.ticketing.usecase.order.ProcessOrderCommandUseCase;
import co.com.ticketing.usecase.order.ReleaseExpiredReservationsUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessorsTest {

    @Mock
    private ProcessOrderCommandUseCase processOrderCommandUseCase;
    @Mock
    private ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private OrderCommandProcessor orderCommandProcessor;

    @BeforeEach
    void setUp() {
        orderCommandProcessor = new OrderCommandProcessor(processOrderCommandUseCase, jsonMapper);
    }

    private static Message message(String body) {
        return Message.builder().messageId("m-1").body(body).build();
    }

    @Test
    void dispatchesTheCommandToTheUseCase() {
        var command = OrderCommand.reserve("o-1");
        var order = Order.request(new OrderPlacement("c", "e", 1, "k", OrderType.PURCHASE), Instant.now());
        when(processOrderCommandUseCase.process(command)).thenReturn(Mono.just(order));

        StepVerifier.create(orderCommandProcessor.apply(message(jsonMapper.writeValueAsString(command)))).verifyComplete();
        verify(processOrderCommandUseCase).process(command);
    }

    @Test
    void businessRejectionsAreAcknowledged() {
        when(processOrderCommandUseCase.process(any()))
                .thenReturn(Mono.error(new BusinessException(BusinessError.INVALID_STATE_TRANSITION)));

        StepVerifier.create(orderCommandProcessor.apply(message("{\"orderId\":\"o-1\",\"action\":\"RESERVE\"}")))
                .verifyComplete();
    }

    @Test
    void technicalFailuresPropagateSoTheMessageIsRetried() {
        when(processOrderCommandUseCase.process(any()))
                .thenReturn(Mono.error(new TechnicalException(TechnicalError.PERSISTENCE_FAILURE, null)));

        StepVerifier.create(orderCommandProcessor.apply(message("{\"orderId\":\"o-1\",\"action\":\"RESERVE\"}")))
                .expectError(TechnicalException.class)
                .verify();
    }

    @Test
    void malformedMessagesFailAndEndUpInTheDeadLetterQueue() {
        StepVerifier.create(orderCommandProcessor.apply(message("not-json")))
                .expectErrorMatches(error -> ((TechnicalException) error).getError() == TechnicalError.INVALID_MESSAGE)
                .verify();
        StepVerifier.create(orderCommandProcessor.apply(message("{\"orderId\":\"o-1\"}")))
                .expectError(TechnicalException.class)
                .verify();
        verifyNoInteractions(processOrderCommandUseCase);
    }

    @Test
    void schedulerTickTriggersTheReleaseProcess() {
        when(releaseExpiredReservationsUseCase.releaseExpired()).thenReturn(Mono.just(3L));
        var processor = new ReservationExpirationProcessor(releaseExpiredReservationsUseCase);

        StepVerifier.create(processor.apply(message("{\"source\":\"scheduler\"}"))).verifyComplete();
        verify(releaseExpiredReservationsUseCase).releaseExpired();
    }
}
