package co.com.ticketing.model.event.gateways;

import co.com.ticketing.model.event.Event;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface EventRepository {

    /** Persists a new event. Fails with {@code EVENT_ALREADY_EXISTS} if the id is taken. */
    Mono<Event> create(Event event);

    /** Strongly consistent read, so inventory counters reflect the latest committed transaction. */
    Mono<Event> findById(String id);

    Flux<Event> findUpcoming(Instant from, int limit);
}
