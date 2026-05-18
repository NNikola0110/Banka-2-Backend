package rs.raf.banka2_bek.notification;

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ izlazni (publisher) deo monolita — Faza 1. Samo MessageConverter:
 * Spring Boot auto-config kreira RabbitTemplate i okaci ovaj converter na njega.
 * Monolit NAMERNO ne deklarise exchange/queue — notification-service je vlasnik
 * topologije. Bez Exchange bean-a RabbitAdmin nema sta da deklarise pri startup-u,
 * pa @SpringBootTest kontekst monolita ne otvara konekciju ka broker-u (testovi
 * rade i bez RabbitMQ-a; RabbitTemplate je lazy, NotificationPublisher dobija
 * auto-config RabbitTemplate sa ovim converter-om).
 */
@Configuration
public class RabbitConfig {

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
