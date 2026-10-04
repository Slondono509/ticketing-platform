package co.com.ticketing.sqs.sender;

import co.com.ticketing.model.common.ex.TechnicalError;
import co.com.ticketing.model.common.ex.TechnicalException;
import co.com.ticketing.model.order.OrderCommand;
import co.com.ticketing.sqs.sender.config.SQSSenderConfig;
import co.com.ticketing.sqs.sender.config.SQSSenderProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SQSOrderCommandPublisherTest {

    private static final SQSSenderProperties PROPERTIES =
            new SQSSenderProperties("us-east-1", "http://localhost:9324", "http://localhost:9324/000000000000/orders");

    @Mock
    private SqsAsyncClient client;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void publishesTheCommandAsJson() {
        var request = ArgumentCaptor.forClass(SendMessageRequest.class);
        when(client.sendMessage(request.capture()))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("m-1").build()));
        var publisher = new SQSOrderCommandPublisher(client, PROPERTIES, jsonMapper);

        StepVerifier.create(publisher.publish(OrderCommand.processPayment("o-1", "tok_ok"))).verifyComplete();

        assertThat(request.getValue().queueUrl()).isEqualTo(PROPERTIES.orderCommandsQueueUrl());
        assertThat(jsonMapper.readValue(request.getValue().messageBody(), OrderCommand.class))
                .isEqualTo(OrderCommand.processPayment("o-1", "tok_ok"));
        assertThat(request.getValue().messageAttributes().get("action").stringValue()).isEqualTo("PROCESS_PAYMENT");
    }

    @Test
    void brokerFailuresAreRetryableTechnicalErrors() {
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SqsException.builder().message("down").build()));
        var publisher = new SQSOrderCommandPublisher(client, PROPERTIES, jsonMapper);

        StepVerifier.create(publisher.publish(OrderCommand.reserve("o-1")))
                .expectErrorMatches(error -> error instanceof TechnicalException technical
                        && technical.getError() == TechnicalError.MESSAGING_FAILURE && technical.isRetryable())
                .verify();
    }

    @Test
    void clientIsBuiltWithAndWithoutLocalEndpoint() {
        var config = new SQSSenderConfig();
        var publisher = mock(MetricPublisher.class);

        try (var local = config.sqsSenderClient(PROPERTIES, publisher);
             var aws = config.sqsSenderClient(new SQSSenderProperties("us-east-1", null, "url"), publisher)) {
            assertThat(local).isNotNull();
            assertThat(aws.serviceClientConfiguration().region().id()).isEqualTo("us-east-1");
        }
    }
}
