package co.com.ticketing.dynamodb.event;

import co.com.ticketing.dynamodb.config.DynamoDBProperties;
import co.com.ticketing.dynamodb.helper.TemplateAdapterOperations;
import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.gateways.EventRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.time.Instant;

@Repository
public class EventDynamoRepository extends TemplateAdapterOperations<Event, EventEntity> implements EventRepository {

    public EventDynamoRepository(DynamoDbEnhancedAsyncClient client, DynamoDBProperties properties) {
        super(client, EventEntity.class, properties.eventsTable(), EventEntity::from, EventEntity::toModel);
    }

    @Override
    public Mono<Event> create(Event event) {
        return create(event, EventEntity.ID, error -> new BusinessException(BusinessError.EVENT_ALREADY_EXISTS));
    }

    @Override
    public Mono<Event> findById(String id) {
        return getById(id, true);
    }

    /** Uses the {@code entityType + startsAt} index instead of a Scan, results come sorted by date. */
    @Override
    public Flux<Event> findUpcoming(Instant from, int limit) {
        var request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.sortGreaterThanOrEqualTo(Key.builder()
                        .partitionValue(EventEntity.ENTITY_TYPE)
                        .sortValue(from.toEpochMilli())
                        .build()))
                .limit(limit)
                .build();
        return queryIndex(EventEntity.UPCOMING_INDEX, request);
    }
}
