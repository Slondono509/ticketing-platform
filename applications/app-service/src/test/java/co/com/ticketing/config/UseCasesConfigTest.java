package co.com.ticketing.config;

import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.order.gateways.OrderCommandPublisher;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.order.gateways.OrderTransitionGateway;
import co.com.ticketing.model.payment.gateways.PaymentGateway;
import co.com.ticketing.usecase.order.PlaceOrderUseCase;
import co.com.ticketing.usecase.order.ProcessOrderCommandUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UseCasesConfigTest {

    @Test
    void useCasesAreWiredWithTheirGateways() {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            assertThat(context.getBeanNamesForType(PlaceOrderUseCase.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(ProcessOrderCommandUseCase.class)).hasSize(1);
            assertThat(context.getBean(java.time.Clock.class).getZone().getId()).isEqualTo("Z");
        }
    }

    @Test
    void reservationPolicyComesFromConfiguration() {
        var policy = new UseCasesConfig().reservationPolicy(Duration.ofMinutes(5), 4);

        assertThat(policy.reservationTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.maxTicketsPerOrder()).isEqualTo(4);
    }

    @Configuration
    @Import(UseCasesConfig.class)
    static class TestConfig {

        @Bean
        EventRepository eventRepository() {
            return mock(EventRepository.class);
        }

        @Bean
        OrderRepository orderRepository() {
            return mock(OrderRepository.class);
        }

        @Bean
        OrderTransitionGateway orderTransitionGateway() {
            return mock(OrderTransitionGateway.class);
        }

        @Bean
        OrderCommandPublisher orderCommandPublisher() {
            return mock(OrderCommandPublisher.class);
        }

        @Bean
        PaymentGateway paymentGateway() {
            return mock(PaymentGateway.class);
        }

        @Bean
        co.com.ticketing.model.order.ReservationPolicy reservationPolicy() {
            return new co.com.ticketing.model.order.ReservationPolicy(Duration.ofMinutes(10), 10);
        }
    }
}
