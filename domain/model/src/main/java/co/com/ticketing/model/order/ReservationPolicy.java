package co.com.ticketing.model.order;

import java.time.Duration;
import java.util.Objects;

/**
 * Business limits of the reservation process.
 *
 * @param reservationTtl     how long reserved tickets are held before returning to the inventory (max 10 minutes)
 * @param maxTicketsPerOrder upper bound of tickets in a single order, protects the inventory from abuse
 */
public record ReservationPolicy(Duration reservationTtl, int maxTicketsPerOrder) {

    public static final Duration MAX_RESERVATION_TTL = Duration.ofMinutes(10);

    public ReservationPolicy {
        Objects.requireNonNull(reservationTtl, "reservationTtl");
        if (reservationTtl.isNegative() || reservationTtl.isZero() || reservationTtl.compareTo(MAX_RESERVATION_TTL) > 0) {
            throw new IllegalArgumentException("reservationTtl must be greater than zero and at most 10 minutes");
        }
        if (maxTicketsPerOrder < 1) {
            throw new IllegalArgumentException("maxTicketsPerOrder must be positive");
        }
    }
}
