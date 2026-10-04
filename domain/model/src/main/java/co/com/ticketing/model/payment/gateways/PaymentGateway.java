package co.com.ticketing.model.payment.gateways;

import co.com.ticketing.model.payment.PaymentCharge;
import co.com.ticketing.model.payment.PaymentResult;
import reactor.core.publisher.Mono;

public interface PaymentGateway {

    /**
     * Charges the order. A declined payment is a normal result; provider outages are signalled as a retryable
     * {@code TechnicalException}.
     */
    Mono<PaymentResult> charge(PaymentCharge request);
}
