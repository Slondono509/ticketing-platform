package co.com.ticketing.dynamodb.support;

import co.com.ticketing.model.common.ex.TechnicalError;
import co.com.ticketing.model.common.ex.TechnicalException;
import co.com.ticketing.model.common.ex.TicketingException;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.concurrent.CompletionException;

public final class DynamoErrorMapper {

    private DynamoErrorMapper() {
    }

    /** Wraps infrastructure errors in a retryable {@link TechnicalException}; domain errors pass through. */
    public static Throwable map(Throwable error) {
        var cause = unwrap(error);
        if (cause instanceof TicketingException ticketingException) {
            return ticketingException;
        }
        return new TechnicalException(TechnicalError.PERSISTENCE_FAILURE, cause);
    }

    public static boolean isConditionalCheckFailure(Throwable error) {
        return unwrap(error) instanceof ConditionalCheckFailedException;
    }

    public static Throwable unwrap(Throwable error) {
        var current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
