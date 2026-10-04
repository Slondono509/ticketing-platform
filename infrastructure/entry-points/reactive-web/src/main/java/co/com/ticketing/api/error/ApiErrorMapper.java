package co.com.ticketing.api.error;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.common.ex.TechnicalException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.codec.CodecException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Translates errors into RFC 9457 problem details. Internal details (stack traces, AWS error messages) are logged
 * but never returned to the client.
 */
@Component
public class ApiErrorMapper {

    private static final Logger log = LogManager.getLogger(ApiErrorMapper.class);
    private static final String RETRY_AFTER_SECONDS = "1";

    public Mono<ServerResponse> toResponse(Throwable error) {
        return switch (error) {
            case BusinessException business -> problem(statusOf(business.getError()), business.getError().name(),
                    business.getMessage());
            case TechnicalException technical when technical.isRetryable() -> {
                log.error("Retryable technical failure", technical);
                yield problem(HttpStatus.SERVICE_UNAVAILABLE, technical.getError().name(),
                        "The service is temporarily unavailable, retry with the same Idempotency-Key");
            }
            case ServerWebInputException input -> problem(HttpStatus.BAD_REQUEST, BusinessError.INVALID_REQUEST.name(),
                    "Malformed request");
            case CodecException codec -> problem(HttpStatus.BAD_REQUEST, BusinessError.INVALID_REQUEST.name(),
                    "Malformed request body");
            default -> {
                log.error("Unexpected failure", error);
                yield problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error");
            }
        };
    }

    private static HttpStatus statusOf(BusinessError error) {
        return switch (error.getCategory()) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
    }

    private static Mono<ServerResponse> problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(code);
        problem.setType(URI.create("urn:ticketing:error:" + code.toLowerCase()));
        var response = ServerResponse.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return response.bodyValue(problem);
    }
}
