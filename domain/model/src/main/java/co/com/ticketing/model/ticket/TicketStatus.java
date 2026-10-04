package co.com.ticketing.model.ticket;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a single ticket. A ticket is in exactly one state at a time and can only follow the
 * transitions declared here.
 *
 * <pre>
 * AVAILABLE ──► RESERVED ──► PENDING_CONFIRMATION ──► SOLD (final, accountable)
 *     │  ▲          │                 │
 *     │  └──────────┴─────────────────┘  (expiration / payment declined)
 *     └──► COMPLIMENTARY (final, not accountable)
 * </pre>
 */
public enum TicketStatus {
    AVAILABLE,
    RESERVED,
    PENDING_CONFIRMATION,
    SOLD,
    COMPLIMENTARY;

    public Set<TicketStatus> allowedTransitions() {
        return switch (this) {
            case AVAILABLE -> EnumSet.of(RESERVED, COMPLIMENTARY);
            case RESERVED -> EnumSet.of(PENDING_CONFIRMATION, AVAILABLE);
            case PENDING_CONFIRMATION -> EnumSet.of(SOLD, AVAILABLE);
            case SOLD, COMPLIMENTARY -> EnumSet.noneOf(TicketStatus.class);
        };
    }

    public boolean canTransitionTo(TicketStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isFinal() {
        return this == SOLD || this == COMPLIMENTARY;
    }

    /** Only SOLD tickets represent revenue; RESERVED and PENDING_CONFIRMATION are not sales. */
    public boolean isAccountable() {
        return this == SOLD;
    }

    /** States that remove a ticket from the sellable inventory without being final. */
    public boolean isOnHold() {
        return this == RESERVED || this == PENDING_CONFIRMATION;
    }
}
