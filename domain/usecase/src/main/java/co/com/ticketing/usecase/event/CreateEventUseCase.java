package co.com.ticketing.usecase.event;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.NewEvent;
import co.com.ticketing.model.event.gateways.EventRepository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.util.UUID;

public class CreateEventUseCase {

    private final EventRepository eventRepository;
    private final Clock clock;

    public CreateEventUseCase(EventRepository eventRepository, Clock clock) {
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    public Mono<Event> create(NewEvent data) {
        return Mono.fromCallable(() -> {
                    var now = clock.instant();
                    data.validate(now);
                    return Event.create(UUID.randomUUID().toString(), data, now);
                })
                // UUID.randomUUID() reads from SecureRandom, which may block: keep it off the event loop.
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(eventRepository::create);
    }
}
