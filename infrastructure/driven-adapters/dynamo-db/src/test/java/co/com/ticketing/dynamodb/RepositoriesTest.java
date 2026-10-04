package co.com.ticketing.dynamodb;

import co.com.ticketing.dynamodb.config.DynamoDBProperties;
import co.com.ticketing.dynamodb.event.EventDynamoRepository;
import co.com.ticketing.dynamodb.event.EventEntity;
import co.com.ticketing.dynamodb.order.OrderDynamoRepository;
import co.com.ticketing.dynamodb.order.OrderEntity;
import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.common.ex.TechnicalError;
import co.com.ticketing.model.common.ex.TechnicalException;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.NewEvent;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderPlacement;
import co.com.ticketing.model.order.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RepositoriesTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final DynamoDBProperties PROPERTIES = new DynamoDBProperties("us-east-1", null, "events", "orders",
            "audit", 4);

    @Mock
    private DynamoDbEnhancedAsyncClient client;
    @Mock
    private DynamoDbAsyncTable<EventEntity> eventTable;
    @Mock
    private DynamoDbAsyncTable<OrderEntity> orderTable;
    @Mock
    private DynamoDbAsyncIndex<EventEntity> eventIndex;
    @Mock
    private DynamoDbAsyncIndex<OrderEntity> orderIndex;

    private EventDynamoRepository events;
    private OrderDynamoRepository orders;

    @BeforeEach
    void setUp() {
        when(client.table(eq("events"), any())).thenAnswer(invocation -> eventTable);
        when(client.table(eq("orders"), any())).thenAnswer(invocation -> orderTable);
        events = new EventDynamoRepository(client, PROPERTIES);
        orders = new OrderDynamoRepository(client, PROPERTIES);
    }

    private static Event event() {
        return Event.create("e-1", new NewEvent("Rock", "Arena", NOW.plus(Duration.ofDays(1)), 10), NOW);
    }

    private static Order order() {
        return Order.request(new OrderPlacement("c-1", "e-1", 2, "k-1", OrderType.PURCHASE), NOW);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createUsesAConditionalPut() {
        var request = ArgumentCaptor.forClass(PutItemEnhancedRequest.class);
        when(eventTable.putItem(request.capture())).thenReturn(CompletableFuture.completedFuture(null));

        StepVerifier.create(events.create(event())).expectNext(event()).verifyComplete();

        assertThat(request.getValue().conditionExpression().expression()).isEqualTo("attribute_not_exists(#pk)");
        assertThat(request.getValue().conditionExpression().expressionNames()).containsEntry("#pk", "id");
        assertThat(((EventEntity) request.getValue().item()).getAvailable()).isEqualTo(10);
    }

    @Test
    void duplicateIdsAreReportedAsBusinessErrors() {
        when(eventTable.putItem(any(PutItemEnhancedRequest.class))).thenReturn(CompletableFuture.failedFuture(
                new CompletionException(ConditionalCheckFailedException.builder().message("exists").build())));
        when(orderTable.putItem(any(PutItemEnhancedRequest.class))).thenReturn(CompletableFuture.failedFuture(
                ConditionalCheckFailedException.builder().message("exists").build()));

        StepVerifier.create(events.create(event()))
                .expectErrorMatches(error -> ((BusinessException) error).is(BusinessError.EVENT_ALREADY_EXISTS))
                .verify();
        StepVerifier.create(orders.create(order()))
                .expectErrorMatches(error -> ((BusinessException) error).is(BusinessError.ORDER_ALREADY_EXISTS))
                .verify();
    }

    @Test
    void infrastructureFailuresBecomeRetryableTechnicalErrors() {
        when(eventTable.putItem(any(PutItemEnhancedRequest.class))).thenReturn(CompletableFuture.failedFuture(
                ProvisionedThroughputExceededException.builder().message("throttled").build()));

        StepVerifier.create(events.create(event()))
                .expectErrorMatches(error -> error instanceof TechnicalException technical
                        && technical.getError() == TechnicalError.PERSISTENCE_FAILURE && technical.isRetryable())
                .verify();
    }

    @Test
    void findByIdUsesStronglyConsistentReads() {
        var request = ArgumentCaptor.forClass(GetItemEnhancedRequest.class);
        when(eventTable.getItem(request.capture()))
                .thenReturn(CompletableFuture.completedFuture(EventEntity.from(event())));
        when(orderTable.getItem(any(GetItemEnhancedRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        StepVerifier.create(events.findById("e-1")).expectNext(event()).verifyComplete();
        StepVerifier.create(orders.findById("missing")).verifyComplete();
        assertThat(request.getValue().consistentRead()).isTrue();
    }

    @Test
    void findUpcomingQueriesTheDateIndex() {
        var request = ArgumentCaptor.forClass(QueryEnhancedRequest.class);
        when(eventTable.index(EventEntity.UPCOMING_INDEX)).thenReturn(eventIndex);
        when(eventIndex.query(request.capture()))
                .thenReturn(SdkPublisher.adapt(Flux.just(Page.create(List.of(EventEntity.from(event()))))));

        StepVerifier.create(events.findUpcoming(NOW, 5)).expectNext(event()).verifyComplete();
        assertThat(request.getValue().limit()).isEqualTo(5);
    }

    @Test
    void findExpiredReservationsQueriesEveryShard() {
        var reserved = order().reserve(NOW, Duration.ofMinutes(10)).next();
        when(orderTable.index(OrderEntity.EXPIRATION_INDEX)).thenReturn(orderIndex);
        when(orderIndex.query(any(QueryEnhancedRequest.class)))
                .thenAnswer(invocation -> SdkPublisher.adapt(Flux.just(
                        Page.create(List.of(OrderEntity.from(reserved, PROPERTIES.expirationShards()))))));

        StepVerifier.create(orders.findExpiredReservations(NOW.plus(Duration.ofMinutes(11)), 10))
                .expectNextCount(PROPERTIES.expirationShards())
                .verifyComplete();
        StepVerifier.create(orders.findExpiredReservations(NOW.plus(Duration.ofMinutes(11)), 2))
                .expectNextCount(2)
                .verifyComplete();
        verify(orderIndex, atLeast(PROPERTIES.expirationShards())).query(any(QueryEnhancedRequest.class));
    }
}
