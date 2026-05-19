package rs.raf.trading.config;

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ izlazni (publisher) deo trading-service — Faza 2f-5a.
 *
 * <p>trading-service publish-uje samo margin-call notifikaciju
 * ({@code MARGIN_ACCOUNT_BLOCKED}) ka {@code notification-service}. Kao i monolitov
 * {@code RabbitConfig}: registruje SAMO {@link MessageConverter} — Spring Boot
 * auto-config kreira {@code RabbitTemplate} i okaci ovaj converter na njega.
 * trading-service NAMERNO ne deklarise exchange/queue ({@code notification-service}
 * je vlasnik topologije); bez {@code Exchange}/{@code Queue} bean-a {@code RabbitAdmin}
 * nema sta da deklarise pri startup-u, pa se {@code @SpringBootTest} kontekst podize
 * bez konekcije ka broker-u (testovi rade i bez RabbitMQ-a — {@code RabbitTemplate}
 * je lazy).
 *
 * <p>Spring Boot 4 koristi Jackson 3, pa je converter {@code JacksonJsonMessageConverter}
 * (ne stari {@code Jackson2JsonMessageConverter}) — paritet sa monolitom i
 * notification-service-om.
 */
@Configuration
public class RabbitConfig {

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
