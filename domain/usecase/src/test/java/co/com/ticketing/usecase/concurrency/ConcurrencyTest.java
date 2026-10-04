package co.com.ticketing.usecase.concurrency;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.NewEvent;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderCommand;
import co.com.ticketing.model.order.OrderPlacement;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderType;
import co.com.ticketing.model.payment.PaymentResult;
import co.com.ticketing.usecase.order.ConfirmOrderUseCase;
import co.com.ticketing.usecase.order.GetOrderUseCase;
import co.com.ticketing.usecase.order.ProcessOrderCommandUseCase;
import co.com.ticketing.usecase.order.ReleaseExpiredReservationsUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static co.com.ticketing.usecase.Fixtures.CLOCK;
import static co.com.ticketing.usecase.Fixtures.NOW;
import static co.com.ticketing.usecase.Fixtures.POLICY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real use cases concurrently on the parallel scheduler against a store with conditional-write semantics,
 * checking the invariants the business cares about: no overselling, idempotency under redelivery, and a single
 * winner when a confirmation races with the expiration process.
 */
class ConcurrencyTest {

    private static final int CAPACITY = 50;
    private static final int BUYERS = 200;
    private static final String EVENT_ID = "hot-event";

    private InMemoryTicketStore store;
    private ProcessOrderCommandUseCase processUseCase;

    @BeforeEach
    void setUp() {
        store = new InMemoryTicketStore();
        store.put(Event.create(EVENT_ID, new NewEvent("Hot", "Arena", NOW.plus(Duration.ofDays(1)), CAPACITY), NOW));
        processUseCase = new ProcessOrderCommandUseCase(store, store,
                charge -> Mono.just(new PaymentResult.Approved("ref")), POLICY, CLOCK);
    }

    @RepeatedTest(5)
    void concurrentPurchasesNeverOversell() {
        var orders = createProcessingOrders(BUYERS, 1);

        runConcurrently(orders.stream().map(order -> OrderCommand.reserve(order.id())).toList());

        assertThat(countByStatus(OrderStatus.RESERVED)).isEqualTo(CAPACITY);
        assertThat(countByStatus(OrderStatus.REJECTED)).isEqualTo(BUYERS - CAPACITY);
        var inventory = store.event(EVENT_ID).inventory();
        assertThat(inventory.available()).isZero();
        assertThat(inventory.reserved()).isEqualTo(CAPACITY);
        assertThat(inventory.total()).isEqualTo(CAPACITY);
    }

    @Test
    void redeliveredCommandsAreAppliedOnlyOnce() {
        var orders = createProcessingOrders(20, 2);
        var commands = orders.stream()
                .flatMap(order -> IntStream.range(0, 3).mapToObj(i -> OrderCommand.reserve(order.id())))
                .toList();

        runConcurrently(commands);

        assertThat(countByStatus(OrderStatus.RESERVED)).isEqualTo(20);
        assertThat(store.event(EVENT_ID).inventory().reserved()).isEqualTo(40);
        assertThat(store.event(EVENT_ID).inventory().available()).isEqualTo(CAPACITY - 40);
    }

    @RepeatedTest(5)
    void confirmationRacingWithExpirationHasASingleWinner() {
        var orders = createProcessingOrders(CAPACITY, 1);
        runConcurrently(orders.stream().map(order -> OrderCommand.reserve(order.id())).toList());
        // The expiration process sees the reservations as expired while customers are still confirming.
        Clock afterExpiry = Clock.fixed(NOW.plus(Duration.ofMinutes(10)), ZoneOffset.UTC);
        var release = new ReleaseExpiredReservationsUseCase(store, store, afterExpiry);
        var confirm = new ConfirmOrderUseCase(new GetOrderUseCase(store), store, command -> Mono.empty(), CLOCK);

        var confirmations = Flux.fromIterable(orders)
                .parallel().runOn(Schedulers.parallel())
                .flatMap(order -> confirm.confirm(order.id(), order.customerId(), "tok_ok")
                        .onErrorResume(error -> Mono.empty()))
                .sequential().then();
        StepVerifier.create(Mono.when(confirmations, release.releaseExpired().subscribeOn(Schedulers.parallel())))
                .verifyComplete();

        var pending = countByStatus(OrderStatus.PENDING_CONFIRMATION);
        var released = countByStatus(OrderStatus.RELEASED);
        var inventory = store.event(EVENT_ID).inventory();
        assertThat(pending + released).isEqualTo(CAPACITY);
        assertThat(inventory.pendingConfirmation()).isEqualTo(pending);
        assertThat(inventory.available()).isEqualTo(released);
        assertThat(inventory.reserved()).isZero();
        assertThat(inventory.total()).isEqualTo(CAPACITY);
    }

    private List<Order> createProcessingOrders(int count, int quantity) {
        return Flux.range(0, count)
                .map(i -> Order.request(new OrderPlacement("customer-" + i, EVENT_ID, quantity, "key-" + i,
                        OrderType.PURCHASE), Instant.from(NOW)))
                .concatMap(store::create)
                .collectList()
                .block();
    }

    private void runConcurrently(List<OrderCommand> commands) {
        var run = Flux.fromIterable(commands)
                .parallel(Runtime.getRuntime().availableProcessors() * 2)
                .runOn(Schedulers.parallel())
                .flatMap(processUseCase::process)
                .sequential()
                .then();
        StepVerifier.create(run).expectComplete().verify(Duration.ofSeconds(30));
    }

    private long countByStatus(OrderStatus status) {
        return store.orders().values().stream().filter(order -> order.status() == status).count();
    }
}
