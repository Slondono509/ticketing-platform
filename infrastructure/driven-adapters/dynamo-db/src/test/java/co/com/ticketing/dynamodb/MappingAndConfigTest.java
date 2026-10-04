package co.com.ticketing.dynamodb;

import co.com.ticketing.dynamodb.config.DynamoDBConfig;
import co.com.ticketing.dynamodb.config.DynamoDBProperties;
import co.com.ticketing.dynamodb.event.EventEntity;
import co.com.ticketing.dynamodb.order.OrderEntity;
import co.com.ticketing.dynamodb.support.DynamoErrorMapper;
import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.NewEvent;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderPlacement;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderType;
import co.com.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.metrics.MetricPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MappingAndConfigTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    @Test
    void eventRoundTrip() {
        var event = Event.create("e-1", new NewEvent("Rock", "Arena", NOW.plus(Duration.ofDays(1)), 10), NOW);

        var entity = EventEntity.from(event);

        assertThat(entity.getEntityType()).isEqualTo(EventEntity.ENTITY_TYPE);
        assertThat(entity.getStartsAt()).isEqualTo(event.startsAt().toEpochMilli());
        assertThat(entity.toModel()).isEqualTo(event);
    }

    @Test
    void eventWithoutVersionDefaultsToZero() {
        var entity = EventEntity.from(Event.create("e-1", new NewEvent("Rock", "Arena", NOW, 1), NOW));
        entity.setVersion(null);

        assertThat(entity.toModel().version()).isZero();
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void everyTicketStatusHasACounterAttribute(TicketStatus status) {
        assertThat(EventEntity.counterAttribute(status)).isNotBlank();
    }

    @Test
    void reservedOrdersAreWrittenToTheSparseExpirationIndex() {
        var processing = Order.request(new OrderPlacement("c-1", "e-1", 2, "k-1", OrderType.PURCHASE), NOW);
        var reserved = processing.reserve(NOW, Duration.ofMinutes(10)).next();
        var pending = reserved.requestConfirmation(NOW).next();

        var reservedEntity = OrderEntity.from(reserved, 8);

        assertThat(reservedEntity.getExpirationShard()).isEqualTo(OrderEntity.shardOf(reserved.id(), 8)).startsWith("SHARD#");
        assertThat(reservedEntity.getExpiresAt()).isEqualTo(reserved.expiresAt().toEpochMilli());
        assertThat(reservedEntity.toModel()).isEqualTo(reserved);
        assertThat(OrderEntity.from(processing, 8).getExpirationShard()).isNull();
        assertThat(OrderEntity.from(pending, 8).getExpiresAt()).isNull();
        assertThat(OrderEntity.from(pending, 8).toModel().status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
    }

    @Test
    void orderWithoutTicketsOrVersionIsMapped() {
        var entity = OrderEntity.from(Order.request(new OrderPlacement("c", "e", 1, "k", OrderType.PURCHASE), NOW), 8);
        entity.setTickets(null);
        entity.setVersion(null);

        assertThat(entity.toModel().tickets()).isEmpty();
        assertThat(entity.toModel().version()).isZero();
    }

    @Test
    void errorMapperUnwrapsCompletionExceptions() {
        var business = new BusinessException(BusinessError.ORDER_NOT_FOUND);

        assertThat(DynamoErrorMapper.map(new CompletionException(business))).isSameAs(business);
        assertThat(DynamoErrorMapper.unwrap(new CompletionException(null))).isInstanceOf(CompletionException.class);
    }

    @Test
    void clientsAreBuiltWithAndWithoutLocalEndpoint() {
        var config = new DynamoDBConfig();
        var publisher = mock(MetricPublisher.class);
        var local = new DynamoDBProperties("us-east-1", "http://localhost:8000", "e", "o", "a", 8);
        var aws = new DynamoDBProperties("us-east-1", "", "e", "o", "a", 8);

        try (var localClient = config.dynamoDbAsyncClient(local, publisher);
             var awsClient = config.dynamoDbAsyncClient(aws, publisher)) {
            assertThat(config.dynamoDbEnhancedAsyncClient(localClient)).isNotNull();
            assertThat(awsClient.serviceClientConfiguration().region().id()).isEqualTo("us-east-1");
        }
    }
}
