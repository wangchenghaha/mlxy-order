package com.malaysia.restaurant.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "restaurant.mq")
public class MqConfig {
    private boolean enabled = false;
    private String printExchange = "restaurant.print.exchange";
    private String printQueue = "restaurant.print.queue";
    private String printRoutingKey = "print.task";
    private String printDeadLetterExchange = "restaurant.print.dlx";
    private String printDeadLetterQueue = "restaurant.print.dlq";
    private String printDeadLetterRoutingKey = "print.task.dead";

    @Bean
    @ConditionalOnProperty(prefix = "restaurant.mq", name = "enabled", havingValue = "true")
    DirectExchange printExchange() {
        return new DirectExchange(printExchange, true, false);
    }

    @Bean
    @ConditionalOnProperty(prefix = "restaurant.mq", name = "enabled", havingValue = "true")
    Queue printQueue() {
        return QueueBuilder.durable(printQueue)
                .deadLetterExchange(printDeadLetterExchange)
                .deadLetterRoutingKey(printDeadLetterRoutingKey)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "restaurant.mq", name = "enabled", havingValue = "true")
    Binding printBinding(@Qualifier("printQueue") Queue printQueue,
                         @Qualifier("printExchange") DirectExchange printExchange) {
        return BindingBuilder.bind(printQueue).to(printExchange).with(printRoutingKey);
    }

    @Bean
    @ConditionalOnProperty(prefix = "restaurant.mq", name = "enabled", havingValue = "true")
    DirectExchange printDeadLetterExchange() {
        return new DirectExchange(printDeadLetterExchange, true, false);
    }

    @Bean
    @ConditionalOnProperty(prefix = "restaurant.mq", name = "enabled", havingValue = "true")
    Queue printDeadLetterQueue() {
        return QueueBuilder.durable(printDeadLetterQueue).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "restaurant.mq", name = "enabled", havingValue = "true")
    Binding printDeadLetterBinding(@Qualifier("printDeadLetterQueue") Queue printDeadLetterQueue,
                                   @Qualifier("printDeadLetterExchange") DirectExchange printDeadLetterExchange) {
        return BindingBuilder.bind(printDeadLetterQueue).to(printDeadLetterExchange).with(printDeadLetterRoutingKey);
    }

    @Bean
    @ConditionalOnProperty(prefix = "restaurant.mq", name = "enabled", havingValue = "true")
    PrintTaskPublisher rabbitPrintTaskPublisher(RabbitTemplate rabbitTemplate) {
        MessagePostProcessor persistent = message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        };
        return taskId -> rabbitTemplate.convertAndSend(printExchange, printRoutingKey, taskId, persistent);
    }

    @Bean
    @ConditionalOnProperty(prefix = "restaurant.mq", name = "enabled", havingValue = "false", matchIfMissing = true)
    PrintTaskPublisher noopPrintTaskPublisher() {
        return taskId -> {
        };
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPrintExchange() {
        return printExchange;
    }

    public void setPrintExchange(String printExchange) {
        this.printExchange = printExchange;
    }

    public String getPrintQueue() {
        return printQueue;
    }

    public void setPrintQueue(String printQueue) {
        this.printQueue = printQueue;
    }

    public String getPrintRoutingKey() {
        return printRoutingKey;
    }

    public void setPrintRoutingKey(String printRoutingKey) {
        this.printRoutingKey = printRoutingKey;
    }

    public String getPrintDeadLetterExchange() {
        return printDeadLetterExchange;
    }

    public void setPrintDeadLetterExchange(String printDeadLetterExchange) {
        this.printDeadLetterExchange = printDeadLetterExchange;
    }

    public String getPrintDeadLetterQueue() {
        return printDeadLetterQueue;
    }

    public void setPrintDeadLetterQueue(String printDeadLetterQueue) {
        this.printDeadLetterQueue = printDeadLetterQueue;
    }

    public String getPrintDeadLetterRoutingKey() {
        return printDeadLetterRoutingKey;
    }

    public void setPrintDeadLetterRoutingKey(String printDeadLetterRoutingKey) {
        this.printDeadLetterRoutingKey = printDeadLetterRoutingKey;
    }

    public interface PrintTaskPublisher {
        void publish(long taskId);
    }
}
