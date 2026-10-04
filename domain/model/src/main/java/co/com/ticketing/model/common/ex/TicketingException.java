package co.com.ticketing.model.common.ex;

/**
 * Root of the domain error hierarchy. It is sealed so entry points can map every possible
 * failure exhaustively with pattern matching.
 */
public sealed class TicketingException extends RuntimeException permits BusinessException, TechnicalException {

    protected TicketingException(String message, Throwable cause) {
        super(message, cause);
    }
}
