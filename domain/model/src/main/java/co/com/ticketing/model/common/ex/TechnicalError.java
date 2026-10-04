package co.com.ticketing.model.common.ex;

public enum TechnicalError {
    CONCURRENT_UPDATE("A concurrent transaction touched the same items", true),
    PERSISTENCE_FAILURE("The persistence layer failed", true),
    MESSAGING_FAILURE("The message broker failed", true),
    PAYMENT_PROVIDER_FAILURE("The payment provider failed", true),
    INVALID_MESSAGE("The message cannot be processed", false);

    private final String message;
    private final boolean retryable;

    TechnicalError(String message, boolean retryable) {
        this.message = message;
        this.retryable = retryable;
    }

    public String getMessage() {
        return message;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
