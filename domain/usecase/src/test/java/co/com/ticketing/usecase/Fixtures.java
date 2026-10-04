package co.com.ticketing.usecase;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.NewEvent;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderPlacement;
import co.com.ticketing.model.order.OrderType;
import co.com.ticketing.model.order.ReservationPolicy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Predicate;

public final class Fixtures {

    public static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    public static final ReservationPolicy POLICY = new ReservationPolicy(Duration.ofMinutes(10), 10);
    public static final String EVENT_ID = "event-1";
    public static final String CUSTOMER_ID = "customer-1";

    private Fixtures() {
    }

    public static Event event(int capacity) {
        return Event.create(EVENT_ID, new NewEvent("Rock Fest", "Arena", NOW.plus(Duration.ofDays(30)), capacity), NOW);
    }

    public static OrderPlacement placement(int quantity) {
        return new OrderPlacement(CUSTOMER_ID, EVENT_ID, quantity, "key-1", OrderType.PURCHASE);
    }

    public static Order processing(int quantity) {
        return Order.request(placement(quantity), NOW);
    }

    public static Order reserved(int quantity) {
        return processing(quantity).reserve(NOW, POLICY.reservationTtl()).next();
    }

    public static Order pending(int quantity) {
        return reserved(quantity).requestConfirmation(NOW).next();
    }

    public static Predicate<Throwable> businessError(BusinessError expected) {
        return error -> error instanceof BusinessException business && business.is(expected);
    }
}
