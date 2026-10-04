package co.com.ticketing.dynamodb.order;

import co.com.ticketing.dynamodb.config.DynamoDBProperties;
import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.common.ex.TechnicalError;
import co.com.ticketing.model.common.ex.TechnicalException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderPlacement;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderTransitionDynamoAdapterTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final DynamoDBProperties PROPERTIES = new DynamoDBProperties("us-east-1", null, "events", "orders",
            "audit", 4);

    @Mock
    private DynamoDbAsyncClient client;

    private OrderTransitionDynamoAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new OrderTransitionDynamoAdapter(client, PROPERTIES);
    }

    private static Order processing() {
        return Order.request(new OrderPlacement("c-1", "e-1", 2, "k-1", OrderType.PURCHASE), NOW);
    }

    private static OrderTransition reserve() {
        return processing().reserve(NOW, Duration.ofMinutes(10));
    }

    @Test
    void reservationWritesOrderInventoryAndAuditAtomically() {
        var request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        when(client.transactWriteItems(request.capture()))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        StepVerifier.create(adapter.apply(reserve()))
                .expectNextMatches(order -> order.status() == OrderStatus.RESERVED)
                .verifyComplete();

        var items = request.getValue().transactItems();
        assertThat(items).hasSize(3);
        var orderPut = items.get(0).put();
        assertThat(orderPut.tableName()).isEqualTo("orders");
        assertThat(orderPut.conditionExpression()).isEqualTo("#status = :expectedStatus AND #version = :expectedVersion");
        assertThat(orderPut.expressionAttributeValues().get(":expectedStatus").s()).isEqualTo("PROCESSING");
        assertThat(orderPut.expressionAttributeValues().get(":expectedVersion").n()).isEqualTo("0");
        assertThat(orderPut.item()).containsKeys("expirationShard", "expiresAt");
        assertThat(orderPut.item().get("version").n()).isEqualTo("1");

        var inventory = items.get(1).update();
        assertThat(inventory.tableName()).isEqualTo("events");
        assertThat(inventory.updateExpression()).isEqualTo("ADD #from :decrement, #to :increment, #version :one");
        assertThat(inventory.conditionExpression()).isEqualTo("attribute_exists(#id) AND #from >= :quantity");
        assertThat(inventory.expressionAttributeNames()).containsEntry("#from", "available").containsEntry("#to", "reserved");
        assertThat(inventory.expressionAttributeValues().get(":decrement").n()).isEqualTo("-2");

        var audit = items.get(2).put();
        assertThat(audit.tableName()).isEqualTo("audit");
        assertThat(audit.item().get("fromStatus").s()).isEqualTo("PROCESSING");
        assertThat(audit.item().get("toStatus").s()).isEqualTo("RESERVED");
        assertThat(audit.item().get("ticketIds").l()).hasSize(2);
        assertThat(audit.item().get("inventoryFrom").s()).isEqualTo("AVAILABLE");
    }

    @Test
    void transitionsWithoutInventoryChangeSkipTheEventUpdate() {
        var request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        when(client.transactWriteItems(request.capture()))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        StepVerifier.create(adapter.apply(processing().reject(NOW, Order.REASON_SOLD_OUT)))
                .expectNextCount(1)
                .verifyComplete();

        var items = request.getValue().transactItems();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).put().item()).doesNotContainKeys("expirationShard", "expiresAt");
        assertThat(items.get(1).put().item().get("reason").s()).isEqualTo(Order.REASON_SOLD_OUT);
    }

    @Test
    void failedOrderConditionMeansTheOrderChanged() {
        failWith("ConditionalCheckFailed", "None", "None");

        StepVerifier.create(adapter.apply(reserve())).expectErrorMatches(business(BusinessError.ORDER_STATE_CHANGED)).verify();
    }

    @Test
    void failedAuditConditionMeansTheOrderChanged() {
        failWith("None", "None", "ConditionalCheckFailed");

        StepVerifier.create(adapter.apply(reserve())).expectErrorMatches(business(BusinessError.ORDER_STATE_CHANGED)).verify();
    }

    @Test
    void failedInventoryConditionMeansNotEnoughTickets() {
        failWith("None", "ConditionalCheckFailed", "None");

        StepVerifier.create(adapter.apply(reserve()))
                .expectErrorMatches(business(BusinessError.INSUFFICIENT_INVENTORY))
                .verify();
    }

    @Test
    void transactionConflictsAreRetryable() {
        failWith("None", "TransactionConflict", "None");

        StepVerifier.create(adapter.apply(reserve())).expectErrorMatches(concurrentUpdate()).verify();
    }

    @Test
    void cancellationWithoutReasonsIsRetryable() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(CompletableFuture.failedFuture(
                TransactionCanceledException.builder().message("canceled").build()));

        StepVerifier.create(adapter.apply(reserve())).expectErrorMatches(concurrentUpdate()).verify();
    }

    @Test
    void otherFailuresArePersistenceErrors() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(CompletableFuture.failedFuture(
                InternalServerErrorException.builder().message("boom").build()));

        StepVerifier.create(adapter.apply(reserve()))
                .expectErrorMatches(error -> error instanceof TechnicalException technical
                        && technical.getError() == TechnicalError.PERSISTENCE_FAILURE)
                .verify();
    }

    private void failWith(String... codes) {
        var reasons = Arrays.stream(codes).map(code -> CancellationReason.builder().code(code).build()).toList();
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(CompletableFuture.failedFuture(
                TransactionCanceledException.builder().message("canceled").cancellationReasons(reasons).build()));
    }

    private static Predicate<Throwable> business(BusinessError expected) {
        return error -> error instanceof BusinessException business && business.is(expected);
    }

    private static Predicate<Throwable> concurrentUpdate() {
        return error -> error instanceof TechnicalException technical
                && technical.getError() == TechnicalError.CONCURRENT_UPDATE && technical.isRetryable();
    }
}
