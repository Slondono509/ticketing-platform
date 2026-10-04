package co.com.ticketing.api.handler;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * Reads and sanitizes the parts of a request that are not validated by the domain (headers, path variables).
 */
final class RequestReader {

    /**
     * Identity of the caller. In AWS it is set by API Gateway from the validated JWT ({@code sub} claim) and any
     * value sent by the client is overwritten, so it cannot be spoofed.
     */
    static final String CUSTOMER_ID_HEADER = "X-Customer-Id";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    private RequestReader() {
    }

    static String header(ServerRequest request, String name) {
        return safe(request.headers().firstHeader(name), name);
    }

    static String pathId(ServerRequest request, String name) {
        return safe(request.pathVariable(name), name);
    }

    static <T> Mono<T> body(ServerRequest request, Class<T> type) {
        return request.bodyToMono(type)
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessError.INVALID_REQUEST,
                        "A request body is required")));
    }

    static int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String safe(String value, String name) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new BusinessException(BusinessError.INVALID_REQUEST,
                    name + " is required and may only contain letters, digits, '.', '_', ':' or '-' (max 64)");
        }
        return value;
    }
}
