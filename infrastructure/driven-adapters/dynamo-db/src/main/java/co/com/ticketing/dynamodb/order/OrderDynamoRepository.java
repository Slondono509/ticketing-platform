package co.com.ticketing.dynamodb.order;

import co.com.ticketing.dynamodb.config.DynamoDBProperties;
import co.com.ticketing.dynamodb.helper.TemplateAdapterOperations;
import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.gateways.OrderRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.time.Instant;

@Repository
public class OrderDynamoRepository extends TemplateAdapterOperations<Order, OrderEntity> implements OrderRepository {

    private final int shards;

    public OrderDynamoRepository(DynamoDbEnhancedAsyncClient client, DynamoDBProperties properties) {
        super(client, OrderEntity.class, properties.ordersTable(),
                order -> OrderEntity.from(order, properties.expirationShards()), OrderEntity::toModel);
        this.shards = properties.expirationShards();
    }

    @Override
    public Mono<Order> create(Order order) {
        return create(order, OrderEntity.ID, error -> new BusinessException(BusinessError.ORDER_ALREADY_EXISTS));
    }

    @Override
    public Mono<Order> findById(String id) {
        return getById(id, true);
    }

    /**
     * Queries every shard of the sparse expiration index in parallel. The index is eventually consistent, so it may
     * return an order that was confirmed a moment ago: the release is a conditional write, which discards it.
     */
    @Override
    public Flux<Order> findExpiredReservations(Instant now, int limit) {
        return Flux.range(0, shards)
                .flatMap(shard -> queryIndex(OrderEntity.EXPIRATION_INDEX, QueryEnhancedRequest.builder()
                        .queryConditional(QueryConditional.sortLessThanOrEqualTo(Key.builder()
                                .partitionValue(OrderEntity.shard(shard))
                                .sortValue(now.toEpochMilli())
                                .build()))
                        .limit(limit)
                        .build()))
                .take(limit);
    }
}
