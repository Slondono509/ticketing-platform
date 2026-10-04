package co.com.ticketing.model.order;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Intent of a customer (or of the box office, for complimentary tickets) to obtain tickets.
 */
public record OrderPlacement(String customerId, String eventId, int quantity, String idempotencyKey, OrderType type) {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    public void validate(ReservationPolicy policy) {
        requireSafeId(customerId, "customerId");
        requireSafeId(eventId, "eventId");
        requireSafeId(idempotencyKey, "Idempotency-Key");
        if (quantity < 1 || quantity > policy.maxTicketsPerOrder()) {
            throw new BusinessException(BusinessError.INVALID_REQUEST,
                    "quantity must be between 1 and " + policy.maxTicketsPerOrder());
        }
    }

    /**
     * The order id is derived from the customer and its idempotency key, so a retried request always maps to the
     * same order and can never create a second one.
     */
    public String orderId() {
        var seed = type + ":" + customerId + ":" + idempotencyKey;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void requireSafeId(String value, String field) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new BusinessException(BusinessError.INVALID_REQUEST,
                    field + " is required and may only contain letters, digits, '.', '_', ':' or '-' (max 64)");
        }
    }
}
