package rs.raf.notification.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import rs.raf.banka2.contracts.NotificationRabbit;

@Configuration
public class RabbitConfig {

    @Bean
    public TopicExchange banka2EventsExchange() {
        return new TopicExchange(NotificationRabbit.EXCHANGE, true, false);
    }

    @Bean
    public Queue emailQueue() {
        // durable=true: poruke prezive restart broker-a / consumer-a.
        return new Queue(NotificationRabbit.EMAIL_QUEUE, true);
    }

    @Bean
    public Binding emailBinding(Queue emailQueue, TopicExchange banka2EventsExchange) {
        return BindingBuilder.bind(emailQueue).to(banka2EventsExchange).with(NotificationRabbit.EMAIL_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
