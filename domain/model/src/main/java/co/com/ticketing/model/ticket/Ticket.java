package co.com.ticketing.model.ticket;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;

import java.util.Objects;

public record Ticket(String id, TicketStatus status) {

    public Ticket {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
    }

    public Ticket transitionTo(TicketStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(BusinessError.INVALID_STATE_TRANSITION,
                    "Ticket %s cannot move from %s to %s".formatted(id, status, target));
        }
        return new Ticket(id, target);
    }
}
