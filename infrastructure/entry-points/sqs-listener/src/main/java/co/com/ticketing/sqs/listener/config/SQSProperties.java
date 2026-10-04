package co.com.ticketing.sqs.listener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param endpoint                 only set for local environments (ElasticMQ); empty in AWS
 * @param visibilityTimeoutSeconds time a message stays invisible while processed; if it is not deleted in that
 *                                 window (failure or crash) SQS delivers it again (at-least-once)
 */
@ConfigurationProperties(prefix = "entrypoint.sqs")
public record SQSProperties(
        String region,
        String endpoint,
        int waitTimeSeconds,
        int visibilityTimeoutSeconds,
        int maxNumberOfMessages,
        int numberOfThreads,
        Queues queues) {

    /**
     * @param orderCommands         order commands published by the API
     * @param reservationExpiration ticks published every minute by EventBridge Scheduler
     */
    public record Queues(String orderCommands, String reservationExpiration) {
    }
}
