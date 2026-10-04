package co.com.ticketing.payment;

import co.com.ticketing.model.common.ex.TechnicalException;
import co.com.ticketing.model.payment.PaymentCharge;
import co.com.ticketing.model.payment.PaymentResult;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(Duration.ofMillis(1));

    @Test
    void approvesRegularTokensWithAStableReference() {
        var charge = new PaymentCharge("o-1", "c-1", 2, "tok_visa");

        var first = gateway.charge(charge).block();
        var second = gateway.charge(charge).block();

        assertThat(first).isInstanceOf(PaymentResult.Approved.class).isEqualTo(second);
    }

    @Test
    void declinesDeclinedTokens() {
        StepVerifier.create(gateway.charge(new PaymentCharge("o-1", "c-1", 1, "tok_declined_card")))
                .expectNext(new PaymentResult.Declined("INSUFFICIENT_FUNDS"))
                .verifyComplete();
    }

    @Test
    void simulatesProviderOutages() {
        StepVerifier.create(gateway.charge(new PaymentCharge("o-1", "c-1", 1, "tok_unavailable")))
                .expectErrorMatches(TechnicalException::isRetryable)
                .verify();
    }

    @Test
    void missingTokenIsTreatedAsApproved() {
        StepVerifier.create(gateway.charge(new PaymentCharge("o-1", "c-1", 1, null)))
                .expectNextMatches(PaymentResult.Approved.class::isInstance)
                .verifyComplete();
    }
}
