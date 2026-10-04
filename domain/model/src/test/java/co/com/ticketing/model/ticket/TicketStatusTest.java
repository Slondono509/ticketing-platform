package co.com.ticketing.model.ticket;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketStatusTest {

    @ParameterizedTest
    @CsvSource({
            "AVAILABLE, RESERVED, true",
            "AVAILABLE, COMPLIMENTARY, true",
            "AVAILABLE, SOLD, false",
            "AVAILABLE, PENDING_CONFIRMATION, false",
            "RESERVED, PENDING_CONFIRMATION, true",
            "RESERVED, AVAILABLE, true",
            "RESERVED, SOLD, false",
            "PENDING_CONFIRMATION, SOLD, true",
            "PENDING_CONFIRMATION, AVAILABLE, true",
            "PENDING_CONFIRMATION, RESERVED, false",
            "SOLD, AVAILABLE, false",
            "COMPLIMENTARY, AVAILABLE, false"
    })
    void followsTheDeclaredStateMachine(TicketStatus from, TicketStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"SOLD", "COMPLIMENTARY"})
    void finalStatesHaveNoTransitions(TicketStatus status) {
        assertThat(status.isFinal()).isTrue();
        assertThat(status.allowedTransitions()).isEmpty();
    }

    @Test
    void onlySoldTicketsAreAccountable() {
        assertThat(TicketStatus.SOLD.isAccountable()).isTrue();
        assertThat(TicketStatus.COMPLIMENTARY.isAccountable()).isFalse();
        assertThat(TicketStatus.RESERVED.isAccountable()).isFalse();
        assertThat(TicketStatus.PENDING_CONFIRMATION.isAccountable()).isFalse();
    }

    @Test
    void reservedAndPendingAreOnHoldButNotFinal() {
        assertThat(TicketStatus.RESERVED.isOnHold()).isTrue();
        assertThat(TicketStatus.PENDING_CONFIRMATION.isOnHold()).isTrue();
        assertThat(TicketStatus.AVAILABLE.isOnHold()).isFalse();
        assertThat(TicketStatus.SOLD.isOnHold()).isFalse();
        assertThat(TicketStatus.RESERVED.isFinal()).isFalse();
    }

    @Test
    void ticketTransitionsAreValidated() {
        var ticket = new Ticket("t-1", TicketStatus.AVAILABLE);

        assertThat(ticket.transitionTo(TicketStatus.RESERVED).status()).isEqualTo(TicketStatus.RESERVED);
        assertThatThrownBy(() -> ticket.transitionTo(TicketStatus.SOLD))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getError())
                .isEqualTo(BusinessError.INVALID_STATE_TRANSITION);
    }
}
