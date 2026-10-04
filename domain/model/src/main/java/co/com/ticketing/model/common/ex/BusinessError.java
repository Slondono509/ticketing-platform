package co.com.ticketing.model.common.ex;

public enum BusinessError {
    INVALID_REQUEST(Category.VALIDATION, "The request is not valid"),
    EVENT_NOT_FOUND(Category.NOT_FOUND, "The event does not exist"),
    EVENT_ALREADY_EXISTS(Category.CONFLICT, "The event already exists"),
    EVENT_ALREADY_STARTED(Category.CONFLICT, "Tickets can no longer be requested for this event"),
    ORDER_NOT_FOUND(Category.NOT_FOUND, "The order does not exist"),
    ORDER_ALREADY_EXISTS(Category.CONFLICT, "An order with the same identifier already exists"),
    INSUFFICIENT_INVENTORY(Category.CONFLICT, "There are not enough tickets available"),
    INVALID_STATE_TRANSITION(Category.CONFLICT, "The order cannot move to the requested state"),
    ORDER_STATE_CHANGED(Category.CONFLICT, "The order was modified concurrently"),
    RESERVATION_EXPIRED(Category.CONFLICT, "The reservation has expired"),
    IDEMPOTENCY_KEY_REUSED(Category.UNPROCESSABLE, "The idempotency key was already used with a different payload");

    public enum Category { VALIDATION, NOT_FOUND, CONFLICT, UNPROCESSABLE }

    private final Category category;
    private final String message;

    BusinessError(Category category, String message) {
        this.category = category;
        this.message = message;
    }

    public Category getCategory() {
        return category;
    }

    public String getMessage() {
        return message;
    }
}
