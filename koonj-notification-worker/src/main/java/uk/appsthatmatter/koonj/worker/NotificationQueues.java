package uk.appsthatmatter.koonj.worker;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class NotificationQueues {
    static final String EXCHANGE = "koonj.notifications.v1";
    static final String EMAIL = "koonj.notifications.email.v1";
    static final String WEBHOOK = "koonj.notifications.webhook.v1";
    private static final String DEAD = "koonj.notifications.invalid.v1";

    @Bean
    Declarables notificationTopology() {
        var exchange = new DirectExchange(EXCHANGE);
        var email = queue(EMAIL);
        var webhook = queue(WEBHOOK);
        var invalid = QueueBuilder.durable(DEAD).quorum().build();
        return new Declarables(exchange, email, webhook, invalid,
                BindingBuilder.bind(email).to(exchange).with(EMAIL),
                BindingBuilder.bind(webhook).to(exchange).with(WEBHOOK),
                BindingBuilder.bind(invalid).to(exchange).with(DEAD));
    }

    private Queue queue(String name) {
        return QueueBuilder.durable(name).quorum()
                .deadLetterExchange(EXCHANGE).deadLetterRoutingKey(DEAD)
                .withArgument("x-delivery-limit", -1).build();
    }
}
