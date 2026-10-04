package co.com.ticketing.dynamodb;

import co.com.ticketing.dynamodb.config.DynamoDBProperties;
import co.com.ticketing.dynamodb.event.EventDynamoRepository;
import co.com.ticketing.dynamodb.order.OrderDynamoRepository;
import co.com.ticketing.dynamodb.order.OrderTransitionDynamoAdapter;
import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.common.ex.TechnicalException;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.NewEvent;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderPlacement;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real conditional writes against DynamoDB Local (tables created by deployment/local/init-dynamodb.sh).
 * Enabled only when DYNAMODB_IT_ENDPOINT is set, see README "Integration tests".
 */
@EnabledIfEnvironmentVariable(named = "DYNAMODB_IT_ENDPOINT", matches = ".+")
class OrderTransitionDynamoIT {

    private static final int CAPACITY = 30;
    private static final int CONCURRENT_ORDERS = 100;

    private static EventDynamoRepository events;
    private static OrderDynamoRepository orders;
    private static OrderTransitionDynamoAdapter transitions;

    @BeforeAll
    static void setUp() {
        var client = DynamoDbAsyncClient.builder()
                .endpointOverride(URI.create(System.getenv("DYNAMODB_IT_ENDPOINT")))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
        var enhanced = DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(client).build();
        var properties = new DynamoDBProperties("us-east-1", null, "ticketing-events", "ticketing-orders",
                "ticketing-order-audit", 8);
        events = new EventDynamoRepository(enhanced, properties);
        orders = new OrderDynamoRepository(enhanced, properties);
        transitions = new OrderTransitionDynamoAdapter(client, properties);
    }

    @Test
    void concurrentReservationsNeverOversell() {
        var now = Instant.now();
        var event = events.create(Event.create(UUID.randomUUID().toString(),
                new NewEvent("IT", "Lab", now.plus(Duration.ofDays(30)), CAPACITY), now)).block();
        var created = Flux.fromStream(IntStream.range(0, CONCURRENT_ORDERS).boxed())
                .flatMap(i -> orders.create(Order.request(new OrderPlacement("it-customer-" + i, event.id(), 1,
                        UUID.randomUUID().toString(), OrderType.PURCHASE), now)))
                .collectList().block();

        List<String> outcomes = Flux.fromIterable(created)
                .flatMap(order -> transitions.apply(order.reserve(now, Duration.ofMinutes(10)))
                        // TransactionConflict is expected under contention: retry like the use case does
                        .retryWhen(Retry.backoff(20, Duration.ofMillis(20)).filter(TechnicalException::isRetryable))
                        .map(reserved -> "RESERVED")
                        .onErrorResume(BusinessException.class, error -> Mono.just(error.getError().name())),
                        CONCURRENT_ORDERS)
                .collectList().block();

        assertThat(outcomes).filteredOn("RESERVED"::equals).hasSize(CAPACITY);
        assertThat(outcomes).filteredOn(BusinessError.INSUFFICIENT_INVENTORY.name()::equals)
                .hasSize(CONCURRENT_ORDERS - CAPACITY);
        var inventory = events.findById(event.id()).block().inventory();
        assertThat(inventory.available()).isZero();
        assertThat(inventory.reserved()).isEqualTo(CAPACITY);
        assertThat(inventory.total()).isEqualTo(CAPACITY);
    }

    @Test
    void staleOrderVersionIsRejectedByOptimisticLocking() {
        var now = Instant.now();
        var event = events.create(Event.create(UUID.randomUUID().toString(),
                new NewEvent("IT", "Lab", now.plus(Duration.ofDays(30)), 10), now)).block();
        var order = orders.create(Order.request(new OrderPlacement("it-customer", event.id(), 2,
                UUID.randomUUID().toString(), OrderType.PURCHASE), now)).block();

        var first = transitions.apply(order.reserve(now, Duration.ofMinutes(10))).block();
        var second = transitions.apply(order.reserve(now, Duration.ofMinutes(10)))
                .map(Order::status)
                .onErrorResume(BusinessException.class, error -> Mono.error(error))
                .materialize().block();

        assertThat(first.status()).isEqualTo(OrderStatus.RESERVED);
        assertThat(second.getThrowable()).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getError())
                .isEqualTo(BusinessError.ORDER_STATE_CHANGED);
        assertThat(events.findById(event.id()).block().inventory().reserved()).isEqualTo(2);
    }

    @Test
    void expiredReservationsAreFoundInTheSparseIndex() {
        var now = Instant.now();
        var event = events.create(Event.create(UUID.randomUUID().toString(),
                new NewEvent("IT", "Lab", now.plus(Duration.ofDays(30)), 10), now)).block();
        var order = orders.create(Order.request(new OrderPlacement("it-customer", event.id(), 1,
                UUID.randomUUID().toString(), OrderType.PURCHASE), now)).block();
        transitions.apply(order.reserve(now.minus(Duration.ofMinutes(11)), Duration.ofMinutes(10))).block();

        var expired = orders.findExpiredReservations(now, 500).map(Order::id).collectList().block();

        assertThat(expired).contains(order.id());
    }
}
