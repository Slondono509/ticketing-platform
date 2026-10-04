package co.com.ticketing.dynamodb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param endpoint          only set for local environments (DynamoDB Local); empty in AWS
 * @param expirationShards  number of partitions of the sparse expiration index, spreads reservation writes
 */
@ConfigurationProperties(prefix = "adapter.dynamodb")
public record DynamoDBProperties(
        String region,
        String endpoint,
        String eventsTable,
        String ordersTable,
        String auditTable,
        int expirationShards) {
}
