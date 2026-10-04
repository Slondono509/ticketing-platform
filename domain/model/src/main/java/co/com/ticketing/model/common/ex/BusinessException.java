package co.com.ticketing.model.common.ex;

/**
 * A rule of the business was violated. Retrying the same operation will not change the outcome.
 */
public final class BusinessException extends TicketingException {

    private final BusinessError error;

    public BusinessException(BusinessError error) {
        this(error, error.getMessage());
    }

    public BusinessException(BusinessError error, String detail) {
        super(detail, null);
        this.error = error;
    }

    public BusinessError getError() {
        return error;
    }

    public boolean is(BusinessError expected) {
        return error == expected;
    }
}
