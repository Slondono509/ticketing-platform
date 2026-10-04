package co.com.ticketing.usecase.support;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.common.ex.TechnicalException;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.time.Duration;

public final class RetryPolicies {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration FIRST_BACKOFF = Duration.ofMillis(50);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(1);

    private RetryPolicies() {
    }

    /** Exponential backoff with jitter for transient infrastructure failures (throttling, transaction conflicts). */
    public static RetryBackoffSpec transientFailures() {
        return Retry.backoff(MAX_ATTEMPTS, FIRST_BACKOFF)
                .maxBackoff(MAX_BACKOFF)
                .jitter(0.5)
                .filter(TechnicalException::isRetryable)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    /**
     * Same as {@link #transientFailures()} but also retries when the order changed between the read and the
     * conditional write, so the operation is re-evaluated against the fresh state.
     */
    public static RetryBackoffSpec transientFailuresOrStaleOrder() {
        return transientFailures().filter(RetryPolicies::isRetryableOrStale);
    }

    private static boolean isRetryableOrStale(Throwable error) {
        return TechnicalException.isRetryable(error)
                || error instanceof BusinessException business && business.is(BusinessError.ORDER_STATE_CHANGED);
    }

    public static boolean isBusiness(Throwable error, BusinessError expected) {
        return error instanceof BusinessException business && business.is(expected);
    }
}
