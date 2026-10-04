package co.com.ticketing.sqs.sender;

import co.com.ticketing.model.common.ex.TechnicalError;
import co.com.ticketing.model.common.ex.TechnicalException;
import co.com.ticketing.model.order.OrderCommand;
import co.com.ticketing.model.order.gateways.OrderCommandPublisher;
import co.com.ticketing.sqs.sender.config.SQSSenderConfig;
import co.com.ticketing.sqs.sender.config.SQSSenderProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@Component
public class SQSOrderCommandPublisher implements OrderCommandPublisher {

    private static final Logger log = LogManager.getLogger(SQSOrderCommandPublisher.class);

    private final SqsAsyncClient client;
    private final SQSSenderProperties properties;
    private final JsonMapper jsonMapper;

    public SQSOrderCommandPublisher(@Qualifier(SQSSenderConfig.SENDER_CLIENT) SqsAsyncClient client,
                                    SQSSenderProperties properties, JsonMapper jsonMapper) {
        this.client = client;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Mono<Void> publish(OrderCommand command) {
        return Mono.fromCallable(() -> buildRequest(command))
                .flatMap(request -> Mono.fromFuture(() -> client.sendMessage(request)))
                .doOnNext(response -> log.debug("Command {} for order {} sent as {}",
                        command.action(), command.orderId(), response.messageId()))
                .onErrorMap(error -> new TechnicalException(TechnicalError.MESSAGING_FAILURE, error))
                .then();
    }

    private SendMessageRequest buildRequest(OrderCommand command) {
        return SendMessageRequest.builder()
                .queueUrl(properties.orderCommandsQueueUrl())
                .messageBody(jsonMapper.writeValueAsString(command))
                .messageAttributes(Map.of("action", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(command.action().name())
                        .build()))
                .build();
    }
}
