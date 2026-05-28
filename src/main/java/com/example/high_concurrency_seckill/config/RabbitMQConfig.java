package com.example.high_concurrency_seckill.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.aopalliance.intercept.MethodInterceptor;

@Slf4j
@Configuration
public class RabbitMQConfig {
    public static final String SECKILL_QUEUE = "seckill.queue";
    public static final String SECKILL_EXCHANGE = "seckill.exchange";
    public static final String SECKILL_ROUTING_KEY = "seckill.order";
    public static final String SECKILL_DLX = "seckill.dlx";
    public static final String SECKILL_DLQ = "seckill.queue.dlq";

    @Bean
    public Queue seckillQueue() {
        return QueueBuilder.durable(SECKILL_QUEUE)
                .deadLetterExchange(SECKILL_DLX)
                .deadLetterRoutingKey(SECKILL_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue seckillDeadLetterQueue() {
        return new Queue(SECKILL_DLQ, true);
    }

    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SECKILL_EXCHANGE);
    }

    @Bean
    public DirectExchange seckillDeadLetterExchange() {
        return new DirectExchange(SECKILL_DLX);
    }

    @Bean
    public Binding binding() {
        return BindingBuilder.bind(seckillQueue())
                .to(seckillExchange())
                .with(SECKILL_ROUTING_KEY);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(seckillDeadLetterQueue())
                .to(seckillDeadLetterExchange())
                .with(SECKILL_ROUTING_KEY);
    }

    @Bean
    public MethodInterceptor retryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxRetries(3)
                .backOffOptions(1000, 2.0, 4000) // 1s, 2s, 4s
                .recoverer(new RejectAndDontRequeueRecoverer()) // 耗尽后拒绝 → DLQ
                .build();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MethodInterceptor retryInterceptor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(new JacksonJsonMessageConverter());
        factory.setAdviceChain(retryInterceptor);
        return factory;
    }

    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(new JacksonJsonMessageConverter());
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            String correlationId = correlationData != null ? correlationData.getId() : null;
            if (ack) {
                log.info("rabbitmq confirm ack=true correlationId={}", correlationId);
            } else {
                log.warn("rabbitmq confirm ack=false correlationId={} cause={}", correlationId, cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> log.warn(
                "rabbitmq return replyCode={} replyText={} exchange={} routingKey={}",
                returned.getReplyCode(),
                returned.getReplyText(),
                returned.getExchange(),
                returned.getRoutingKey()
        ));
        return rabbitTemplate;
    }

}
