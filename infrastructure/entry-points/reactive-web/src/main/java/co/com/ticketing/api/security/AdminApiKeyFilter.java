package co.com.ticketing.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Protects back-office routes (create events, issue complimentary tickets) with an API key. The key is injected
 * from AWS Secrets Manager through the ECS task definition; it is never stored in the repository. If no key is
 * configured the filter fails closed and rejects every request.
 * <p>
 * In production this would be replaced by OAuth2 scopes validated by API Gateway / Cognito.
 */
@Component
public class AdminApiKeyFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    public static final String API_KEY_HEADER = "X-Api-Key";

    private final byte[] expectedKey;

    public AdminApiKeyFilter(@Value("${security.admin-api-key:}") String adminApiKey) {
        this.expectedKey = adminApiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        var provided = request.headers().firstHeader(API_KEY_HEADER);
        if (expectedKey.length == 0 || provided == null
                || !MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8))) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "A valid API key is required");
            problem.setTitle("UNAUTHORIZED");
            return ServerResponse.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyValue(problem);
        }
        return next.handle(request);
    }
}
