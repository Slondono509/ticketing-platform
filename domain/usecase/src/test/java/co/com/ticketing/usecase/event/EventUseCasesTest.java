package co.com.ticketing.usecase.event;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.EventAvailability;
import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.event.NewEvent;
import co.com.ticketing.model.event.gateways.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static co.com.ticketing.usecase.Fixtures.CLOCK;
import static co.com.ticketing.usecase.Fixtures.EVENT_ID;
import static co.com.ticketing.usecase.Fixtures.NOW;
import static co.com.ticketing.usecase.Fixtures.businessError;
import static co.com.ticketing.usecase.Fixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventUseCasesTest {

    @Mock
    private EventRepository eventRepository;

    private CreateEventUseCase createEventUseCase;
    private QueryEventsUseCase queryEventsUseCase;

    @BeforeEach
    void setUp() {
        createEventUseCase = new CreateEventUseCase(eventRepository, CLOCK);
        queryEventsUseCase = new QueryEventsUseCase(eventRepository, CLOCK);
    }

    @Test
    void createsAnEventWithItsInitialInventory() {
        when(eventRepository.create(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(createEventUseCase.create(new NewEvent("Rock", "Arena", NOW.plus(Duration.ofDays(1)), 50)))
                .assertNext(event -> {
                    assertThat(event.id()).isNotBlank();
                    assertThat(event.inventory()).isEqualTo(Inventory.of(50));
                    assertThat(event.createdAt()).isEqualTo(NOW);
                })
                .verifyComplete();
    }

    @Test
    void invalidEventIsNotPersisted() {
        StepVerifier.create(createEventUseCase.create(new NewEvent("Rock", "Arena", NOW.minusSeconds(1), 50)))
                .expectErrorMatches(businessError(BusinessError.INVALID_REQUEST))
                .verify();
        verify(eventRepository, never()).create(any());
    }

    @Test
    void getByIdFailsWhenTheEventDoesNotExist() {
        when(eventRepository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(queryEventsUseCase.getById("missing"))
                .expectErrorMatches(businessError(BusinessError.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void listUpcomingClampsThePageSize() {
        var limit = ArgumentCaptor.forClass(Integer.class);
        when(eventRepository.findUpcoming(any(), limit.capture())).thenReturn(Flux.just(event(10)));

        StepVerifier.create(queryEventsUseCase.listUpcoming(10_000)).expectNextCount(1).verifyComplete();
        StepVerifier.create(queryEventsUseCase.listUpcoming(-1)).expectNextCount(1).verifyComplete();

        assertThat(limit.getAllValues()).containsExactly(QueryEventsUseCase.MAX_PAGE_SIZE, 1);
    }

    @Test
    void availabilityReflectsTheCurrentInventory() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(event(10)));

        StepVerifier.create(queryEventsUseCase.getAvailability(EVENT_ID))
                .assertNext(availability -> {
                    assertThat(availability.inventory().available()).isEqualTo(10);
                    assertThat(availability.asOf()).isEqualTo(NOW);
                })
                .verifyComplete();
    }

    @Test
    void watchAvailabilityOnlyEmitsChanges() {
        var initial = event(10);
        var changed = withInventory(initial, new Inventory(8, 2, 0, 0, 0));
        when(eventRepository.findById(EVENT_ID)).thenReturn(
                Mono.just(initial), Mono.just(initial), Mono.just(changed), Mono.just(changed));

        StepVerifier.withVirtualTime(() -> queryEventsUseCase.watchAvailability(EVENT_ID, Duration.ofSeconds(1)).take(2))
                .expectSubscription()
                .assertNext(availability -> assertThat(availability.inventory().available()).isEqualTo(10))
                .thenAwait(Duration.ofSeconds(2))
                .assertNext(availability -> assertThat(availability.inventory().available()).isEqualTo(8))
                .verifyComplete();
    }

    @Test
    void availabilityRecordComparesByInventory() {
        var availability = EventAvailability.of(event(10), NOW);

        assertThat(availability.inventory()).isEqualTo(Inventory.of(10));
    }

    private static Event withInventory(Event event, Inventory inventory) {
        return new Event(event.id(), event.name(), event.venue(), event.startsAt(), event.totalCapacity(), inventory,
                event.createdAt(), event.version() + 1);
    }
}
