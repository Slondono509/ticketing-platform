package co.com.ticketing.sqs.listener;

import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.common.ex.TechnicalError;
import co.com.ticketing.model.common.ex.TechnicalException;
import co.com.ticketing.model.order.OrderCommand;
import co.com.ticketing.usecase.order.ProcessOrderCommandUseCase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.Function;

/**
 * Consumes the order queue. Business rejections are final, so the message is acknowledged; technical failures
 * propagate, leaving the message in the queue to be retried and, after the configured attempts, moved to the DLQ.
 */
@Component
public class OrderCommandProcessor implements Function<Message, Mono<Void>> {

    private static final Logger log = LogManager.getLogger(OrderCommandProcessor.class);

    private final ProcessOrderCommandUseCase processOrderCommandUseCase;
    private final JsonMapper jsonMapper;

    public OrderCommandProcessor(ProcessOrderCommandUseCase processOrderCommandUseCase, JsonMapper jsonMapper) {
        this.processOrderCommandUseCase = processOrderCommandUseCase;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Mono<Void> apply(Message message) {
        return Mono.fromCallable(() -> parse(message))
                .flatMap(processOrderCommandUseCase::process)
                .doOnNext(order -> log.info("Order {} processed, status {}", order.id(), order.status()))
                .onErrorResume(BusinessException.class, error -> {
                    log.warn("Command in message {} rejected: {}", message.messageId(), error.getError());
                    return Mono.empty();
                })
                .then();
    }

    private OrderCommand parse(Message message) {
        try {
            var command = jsonMapper.readValue(message.body(), OrderCommand.class);
            if (command.orderId() == null || command.action() == null) {
                throw new IllegalArgumentException("orderId and action are required");
            }
            return command;
        } catch (RuntimeException error) {
            throw new TechnicalException(TechnicalError.INVALID_MESSAGE, error);
        }
    }
}
