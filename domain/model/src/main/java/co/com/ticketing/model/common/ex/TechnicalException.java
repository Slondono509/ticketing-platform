package co.com.ticketing.model.common.ex;

/**
 * An infrastructure failure. When {@link TechnicalError#isRetryable()} is true the operation can be safely retried.
 */
public final class TechnicalException extends TicketingException {

    private final TechnicalError error;

    public TechnicalException(TechnicalError error, Throwable cause) {
        super(error.getMessage(), cause);
        this.error = error;
    }

    public TechnicalError getError() {
        return error;
    }

    public boolean isRetryable() {
        return error.isRetryable();
    }

    public static boolean isRetryable(Throwable throwable) {
        return throwable instanceof TechnicalException technical && technical.isRetryable();
    }
}
