package co.com.ticketing.usecase.event;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.EventAvailability;
import co.com.ticketing.model.event.gateways.EventRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;

public class QueryEventsUseCase {

    public static final int MAX_PAGE_SIZE = 100;

    private final EventRepository eventRepository;
    private final Clock clock;

    public QueryEventsUseCase(EventRepository eventRepository, Clock clock) {
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    public Mono<Event> getById(String eventId) {
        return eventRepository.findById(eventId)
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessError.EVENT_NOT_FOUND)));
    }

    public Flux<Event> listUpcoming(int limit) {
        var pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        return eventRepository.findUpcoming(clock.instant(), pageSize);
    }

    /** Current availability, read with strong consistency so it includes every committed reservation. */
    public Mono<EventAvailability> getAvailability(String eventId) {
        return getById(eventId).map(event -> EventAvailability.of(event, clock.instant()));
    }

    /**
     * Emits the availability immediately and then every time it changes, polling at {@code interval}.
     * Ticks are dropped instead of queued when a read is slower than the interval.
     */
    public Flux<EventAvailability> watchAvailability(String eventId, Duration interval) {
        return getAvailability(eventId)
                .concatWith(Flux.interval(interval)
                        .onBackpressureDrop()
                        .concatMap(tick -> getAvailability(eventId), 1))
                .distinctUntilChanged(EventAvailability::inventory);
    }
}
