package co.com.ticketing.dynamodb.order;

import co.com.ticketing.dynamodb.config.DynamoDBProperties;
import co.com.ticketing.dynamodb.event.EventEntity;
import co.com.ticketing.dynamodb.support.DynamoErrorMapper;
import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.common.ex.TechnicalError;
import co.com.ticketing.model.common.ex.TechnicalException;
import co.com.ticketing.model.order.InventoryDelta;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.gateways.OrderTransitionGateway;
import co.com.ticketing.model.ticket.Ticket;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies an {@link OrderTransition} with a single DynamoDB transaction made of:
 * <ol>
 *     <li>Put of the order, conditioned on the status and version that were read (optimistic locking).</li>
 *     <li>Update of the event counters with {@code ADD}, conditioned on the source counter covering the quantity
 *     (conditional write: this is what makes overselling impossible).</li>
 *     <li>Put of an append-only audit record, keyed by order and version.</li>
 * </ol>
 * Either the three writes succeed or none of them is applied.
 */
@Component
public class OrderTransitionDynamoAdapter implements OrderTransitionGateway {

    private static final TableSchema<OrderEntity> ORDER_SCHEMA = TableSchema.fromBean(OrderEntity.class);
    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private final DynamoDbAsyncClient client;
    private final DynamoDBProperties properties;

    public OrderTransitionDynamoAdapter(DynamoDbAsyncClient client, DynamoDBProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public Mono<Order> apply(OrderTransition transition) {
        return Mono.fromCallable(() -> buildRequest(transition))
                .flatMap(request -> Mono.fromFuture(() -> client.transactWriteItems(request)))
                .thenReturn(transition.next())
                .onErrorMap(error -> mapError(error, transition));
    }

    TransactWriteItemsRequest buildRequest(OrderTransition transition) {
        List<TransactWriteItem> items = new ArrayList<>();
        items.add(TransactWriteItem.builder().put(orderPut(transition)).build());
        transition.inventoryChange()
                .map(delta -> inventoryUpdate(transition.next().eventId(), delta))
                .ifPresent(update -> items.add(TransactWriteItem.builder().update(update).build()));
        items.add(TransactWriteItem.builder().put(auditPut(transition)).build());
        return TransactWriteItemsRequest.builder().transactItems(items).build();
    }

    private Put orderPut(OrderTransition transition) {
        var previous = transition.previous();
        var item = ORDER_SCHEMA.itemToMap(OrderEntity.from(transition.next(), properties.expirationShards()), true);
        return Put.builder()
                .tableName(properties.ordersTable())
                .item(item)
                .conditionExpression("#status = :expectedStatus AND #version = :expectedVersion")
                .expressionAttributeNames(Map.of("#status", OrderEntity.STATUS, "#version", OrderEntity.VERSION))
                .expressionAttributeValues(Map.of(
                        ":expectedStatus", s(previous.status().name()),
                        ":expectedVersion", n(previous.version())))
                .build();
    }

    private Update inventoryUpdate(String eventId, InventoryDelta delta) {
        return Update.builder()
                .tableName(properties.eventsTable())
                .key(Map.of(EventEntity.ID, s(eventId)))
                .updateExpression("ADD #from :decrement, #to :increment, #version :one")
                .conditionExpression("attribute_exists(#id) AND #from >= :quantity")
                .expressionAttributeNames(Map.of(
                        "#id", EventEntity.ID,
                        "#from", EventEntity.counterAttribute(delta.from()),
                        "#to", EventEntity.counterAttribute(delta.to()),
                        "#version", EventEntity.VERSION))
                .expressionAttributeValues(Map.of(
                        ":decrement", n(-delta.quantity()),
                        ":increment", n(delta.quantity()),
                        ":quantity", n(delta.quantity()),
                        ":one", n(1)))
                .build();
    }

    private Put auditPut(OrderTransition transition) {
        var previous = transition.previous();
        var next = transition.next();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("orderId", s(next.id()));
        item.put("sequence", n(next.version()));
        item.put("eventId", s(next.eventId()));
        item.put("customerId", s(next.customerId()));
        item.put("fromStatus", s(previous.status().name()));
        item.put("toStatus", s(next.status().name()));
        item.put("quantity", n(next.quantity()));
        item.put("ticketIds", AttributeValue.builder()
                .l(next.tickets().stream().map(Ticket::id).map(OrderTransitionDynamoAdapter::s).toList())
                .build());
        item.put("occurredAt", s(transition.occurredAt().toString()));
        if (transition.reason() != null) {
            item.put("reason", s(transition.reason()));
        }
        transition.inventoryChange().ifPresent(delta -> {
            item.put("inventoryFrom", s(delta.from().name()));
            item.put("inventoryTo", s(delta.to().name()));
        });
        return Put.builder()
                .tableName(properties.auditTable())
                .item(item)
                .conditionExpression("attribute_not_exists(#sequence)")
                .expressionAttributeNames(Map.of("#sequence", "sequence"))
                .build();
    }

    private Throwable mapError(Throwable error, OrderTransition transition) {
        if (!(DynamoErrorMapper.unwrap(error) instanceof TransactionCanceledException canceled)) {
            return DynamoErrorMapper.map(error);
        }
        List<String> codes = canceled.hasCancellationReasons()
                ? canceled.cancellationReasons().stream().map(CancellationReason::code).toList()
                : List.of();
        if (codes.isEmpty()) {
            return new TechnicalException(TechnicalError.CONCURRENT_UPDATE, canceled);
        }
        var orderIndex = 0;
        var auditIndex = codes.size() - 1;
        if (failed(codes, orderIndex) || failed(codes, auditIndex)) {
            return new BusinessException(BusinessError.ORDER_STATE_CHANGED);
        }
        if (transition.inventoryChange().isPresent() && failed(codes, 1)) {
            return new BusinessException(BusinessError.INSUFFICIENT_INVENTORY);
        }
        return new TechnicalException(TechnicalError.CONCURRENT_UPDATE, canceled);
    }

    private static boolean failed(List<String> codes, int index) {
        return index < codes.size() && CONDITIONAL_CHECK_FAILED.equals(codes.get(index));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
