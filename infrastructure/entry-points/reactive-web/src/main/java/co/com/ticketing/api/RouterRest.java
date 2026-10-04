package co.com.ticketing.api;

import co.com.ticketing.api.error.ApiErrorMapper;
import co.com.ticketing.api.handler.EventHandler;
import co.com.ticketing.api.handler.OrderHandler;
import co.com.ticketing.api.security.AdminApiKeyFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Configuration
public class RouterRest {

    public static final String BASE_PATH = "/api/v1";

    @Bean
    public RouterFunction<ServerResponse> routerFunction(EventHandler eventHandler, OrderHandler orderHandler,
                                                         AdminApiKeyFilter adminApiKeyFilter,
                                                         ApiErrorMapper errorMapper) {
        RouterFunction<ServerResponse> backOffice = route(POST(BASE_PATH + "/events"), eventHandler::create)
                .andRoute(POST(BASE_PATH + "/events/{eventId}/complimentary-tickets"), eventHandler::issueComplimentary)
                .filter(adminApiKeyFilter);

        RouterFunction<ServerResponse> publicApi = route(GET(BASE_PATH + "/events"), eventHandler::listUpcoming)
                .andRoute(GET(BASE_PATH + "/events/{eventId}"), eventHandler::get)
                .andRoute(GET(BASE_PATH + "/events/{eventId}/availability"), eventHandler::availability)
                .andRoute(GET(BASE_PATH + "/events/{eventId}/availability/stream"), eventHandler::availabilityStream)
                .andRoute(POST(BASE_PATH + "/orders"), orderHandler::place)
                .andRoute(GET(BASE_PATH + "/orders/{orderId}"), orderHandler::get)
                .andRoute(POST(BASE_PATH + "/orders/{orderId}/confirm"), orderHandler::confirm);

        return backOffice.and(publicApi)
                .filter((request, next) -> next.handle(request).onErrorResume(errorMapper::toResponse));
    }
}
