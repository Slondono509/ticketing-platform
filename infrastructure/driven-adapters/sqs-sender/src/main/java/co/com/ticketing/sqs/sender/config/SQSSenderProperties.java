package co.com.ticketing.sqs.sender.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param endpoint              only set for local environments (ElasticMQ); empty in AWS
 * @param orderCommandsQueueUrl queue consumed by the order processor
 */
@ConfigurationProperties(prefix = "adapter.sqs")
public record SQSSenderProperties(
        String region,
        String endpoint,
        String orderCommandsQueueUrl) {
}
