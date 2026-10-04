package co.com.ticketing.sqs.listener.config;

import co.com.ticketing.sqs.listener.OrderCommandProcessor;
import co.com.ticketing.sqs.listener.ReservationExpirationProcessor;
import co.com.ticketing.sqs.listener.helper.SQSListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;

import java.net.URI;

@Configuration
public class SQSConfig {

    public static final String LISTENER_CLIENT = "sqsListenerClient";

    @Bean(destroyMethod = "stop")
    public SQSListener orderCommandsListener(@Qualifier(LISTENER_CLIENT) SqsAsyncClient client,
                                             SQSProperties properties, OrderCommandProcessor processor,
                                             MeterRegistry meterRegistry) {
        return new SQSListener(client, properties, properties.queues().orderCommands(), processor, meterRegistry).start();
    }

    @Bean(destroyMethod = "stop")
    public SQSListener reservationExpirationListener(@Qualifier(LISTENER_CLIENT) SqsAsyncClient client,
                                                     SQSProperties properties,
                                                     ReservationExpirationProcessor processor,
                                                     MeterRegistry meterRegistry) {
        return new SQSListener(client, properties, properties.queues().reservationExpiration(), processor, meterRegistry).start();
    }

    @Bean(name = LISTENER_CLIENT)
    public SqsAsyncClient sqsListenerClient(SQSProperties properties, MetricPublisher publisher) {
        SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                .region(Region.of(properties.region()))
                .overrideConfiguration(o -> o.addMetricPublisher(publisher))
                .credentialsProvider(DefaultCredentialsProvider.builder().build());
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }
}
