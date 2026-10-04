package co.com.ticketing.sqs.listener;

import co.com.ticketing.usecase.order.ReleaseExpiredReservationsUseCase;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.function.Function;

/**
 * Consumes the ticks that EventBridge Scheduler publishes every minute. The body is irrelevant: each tick triggers
 * one release run. Going through SQS (instead of an in-process timer) means only one instance handles each tick,
 * failed runs are retried, and the schedule lives in the infrastructure, not in the code.
 */
@Component
public class ReservationExpirationProcessor implements Function<Message, Mono<Void>> {

    private final ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase;

    public ReservationExpirationProcessor(ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase) {
        this.releaseExpiredReservationsUseCase = releaseExpiredReservationsUseCase;
    }

    @Override
    public Mono<Void> apply(Message message) {
        return releaseExpiredReservationsUseCase.releaseExpired().then();
    }
}
