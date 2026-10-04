package co.com.ticketing.config;

import co.com.ticketing.model.order.ReservationPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.time.Clock;
import java.time.Duration;

@Configuration
@ComponentScan(basePackages = "co.com.ticketing.usecase",
        includeFilters = {
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = "^.+UseCase$")
        },
        useDefaultFilters = false)
public class UseCasesConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ReservationPolicy reservationPolicy(@Value("${ticketing.reservation.ttl:10m}") Duration ttl,
                                               @Value("${ticketing.reservation.max-tickets-per-order:10}") int maxTickets) {
        return new ReservationPolicy(ttl, maxTickets);
    }
}
