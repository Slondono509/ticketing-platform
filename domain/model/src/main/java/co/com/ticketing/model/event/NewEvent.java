package co.com.ticketing.model.event;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;

import java.time.Instant;

public record NewEvent(String name, String venue, Instant startsAt, int totalCapacity) {

    public static final int MAX_TEXT_LENGTH = 200;
    public static final int MAX_CAPACITY = 1_000_000;

    public void validate(Instant now) {
        requireText(name, "name");
        requireText(venue, "venue");
        if (startsAt == null || !startsAt.isAfter(now)) {
            throw new BusinessException(BusinessError.INVALID_REQUEST, "startsAt must be a future date");
        }
        if (totalCapacity < 1 || totalCapacity > MAX_CAPACITY) {
            throw new BusinessException(BusinessError.INVALID_REQUEST,
                    "totalCapacity must be between 1 and " + MAX_CAPACITY);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT_LENGTH) {
            throw new BusinessException(BusinessError.INVALID_REQUEST,
                    field + " is required and must have at most " + MAX_TEXT_LENGTH + " characters");
        }
    }
}
