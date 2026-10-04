package co.com.ticketing.payment;

import co.com.ticketing.model.common.ex.TechnicalError;
import co.com.ticketing.model.common.ex.TechnicalException;
import co.com.ticketing.model.payment.PaymentCharge;
import co.com.ticketing.model.payment.PaymentResult;
import co.com.ticketing.model.payment.gateways.PaymentGateway;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Stand-in for a real payment provider (e.g. a PSP called through a WebClient). Its behaviour is driven by the
 * payment token so every flow can be demonstrated:
 * <ul>
 *     <li>{@code tok_declined*}: the payment is declined and the tickets return to the inventory.</li>
 *     <li>{@code tok_unavailable*}: the provider fails, the message is retried and finally lands in the DLQ.</li>
 *     <li>anything else: approved.</li>
 * </ul>
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final Logger log = LogManager.getLogger(SimulatedPaymentGateway.class);

    private final Duration latency;

    public SimulatedPaymentGateway(@Value("${adapter.payment.simulated-latency:200ms}") Duration latency) {
        this.latency = latency;
    }

    @Override
    public Mono<PaymentResult> charge(PaymentCharge request) {
        return Mono.delay(latency).then(Mono.fromCallable(() -> decide(request)));
    }

    private PaymentResult decide(PaymentCharge request) {
        var token = request.paymentToken() == null ? "" : request.paymentToken();
        if (token.startsWith("tok_unavailable")) {
            throw new TechnicalException(TechnicalError.PAYMENT_PROVIDER_FAILURE,
                    new IllegalStateException("Simulated provider outage"));
        }
        if (token.startsWith("tok_declined")) {
            log.info("Payment declined for order {}", request.orderId());
            return new PaymentResult.Declined("INSUFFICIENT_FUNDS");
        }
        log.info("Payment approved for order {}", request.orderId());
        return new PaymentResult.Approved(UUID.nameUUIDFromBytes(request.orderId().getBytes()).toString());
    }
}
