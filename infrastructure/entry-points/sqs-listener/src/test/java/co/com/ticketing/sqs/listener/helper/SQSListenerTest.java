package co.com.ticketing.sqs.listener.helper;

import co.com.ticketing.sqs.listener.config.SQSConfig;
import co.com.ticketing.sqs.listener.config.SQSProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SQSListenerTest {

    private static final String QUEUE_URL = "http://localhost:9324/000000000000/orders";
    private static final SQSProperties PROPERTIES = new SQSProperties("us-east-1", "http://localhost:9324", 1, 30, 10, 1,
            new SQSProperties.Queues(QUEUE_URL, "http://localhost:9324/000000000000/expiration"));

    @Mock
    private SqsAsyncClient client;

    private void receive(Message... messages) {
        when(client.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(ReceiveMessageResponse.builder().messages(messages).build()));
    }

    @Test
    void successfullyProcessedMessagesAreDeleted() {
        receive(Message.builder().messageId("m-1").receiptHandle("r-1").body("{}").build());
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));
        var listener = new SQSListener(client, PROPERTIES, QUEUE_URL, message -> Mono.empty(), new SimpleMeterRegistry());

        StepVerifier.create(listener.listen()).verifyComplete();

        verify(client).deleteMessage(argThat((DeleteMessageRequest request) -> request.receiptHandle().equals("r-1")
                && request.queueUrl().equals(QUEUE_URL)));
    }

    @Test
    void failedMessagesAreLeftInTheQueueForRedelivery() {
        receive(Message.builder().messageId("m-1").receiptHandle("r-1").body("{}").build());
        var listener = new SQSListener(client, PROPERTIES, QUEUE_URL,
                message -> Mono.error(new IllegalStateException("boom")), new SimpleMeterRegistry());

        StepVerifier.create(listener.listen()).verifyComplete();

        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void startPollsContinuouslyAndStopReleasesResources() {
        receive();
        var listener = new SQSListener(client, PROPERTIES, QUEUE_URL, message -> Mono.empty(), new SimpleMeterRegistry())
                .start();

        verify(client, timeout(2000).atLeast(2)).receiveMessage(any(ReceiveMessageRequest.class));
        listener.stop();
    }

    @Test
    void pollingErrorsDoNotStopTheListener() {
        when(client.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("network")))
                .thenReturn(CompletableFuture.completedFuture(ReceiveMessageResponse.builder().build()));
        var listener = new SQSListener(client, PROPERTIES, QUEUE_URL, message -> Mono.empty(), new SimpleMeterRegistry())
                .start();

        verify(client, timeout(Duration.ofSeconds(3).toMillis()).atLeast(2)).receiveMessage(any(ReceiveMessageRequest.class));
        listener.stop();
        verify(client, after(100).atLeastOnce()).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void configBuildsClientsAndListeners() {
        var config = new SQSConfig();
        try (var sqs = config.sqsListenerClient(PROPERTIES, mock(MetricPublisher.class));
             var aws = config.sqsListenerClient(new SQSProperties("us-east-1", null, 1, 30, 10, 1, PROPERTIES.queues()),
                     mock(MetricPublisher.class))) {
            assertThat(sqs).isNotNull();
            assertThat(aws.serviceClientConfiguration().region().id()).isEqualTo("us-east-1");
        }
        new SQSListener(client, PROPERTIES, QUEUE_URL, message -> Mono.empty(), new SimpleMeterRegistry()).stop();
    }
}
