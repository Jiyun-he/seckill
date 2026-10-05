package com.example.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.example.seckill.common.SeckillOrderStatus;
import com.example.seckill.config.RabbitMQConfiguration;
import com.example.seckill.service.SeckillService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

/**
 * confirm 回调的补偿边界测试（#21~#23）。
 *
 * <p>锁定「只有消息确定未发出才立即补偿」这条边界：ack → 置 CONFIRMED；
 * nack → 不做任何断言，库存与占位保持、状态停留 PENDING 交对账；
 * 消息无法路由被退回 → 确定未入队，立即补偿并置 FAILED。</p>
 *
 * <p>mock {@link RabbitTemplate} 拦截投递并捕获 {@link CorrelationData}，
 * 由测试手动完成其 future，模拟 broker / 框架可能给出的三种回音。</p>
 *
 * @author jiyunhe
 */
@SpringBootTest(classes = SeckillTestApplication.class)
class SeckillConfirmCallbackTest extends AbstractIntegrationTest {

    private static final long USER_ID = 2001L;
    private static final long GOODS_ID = 1L;
    private static final String STOCK_KEY = "seckill:stock:1:20260101000000";
    private static final String ORDERED_KEY = "seckill:ordered:1:20260101000000";
    private static final String ORDER_KEY_PREFIX = "seckill:order:";

    @Autowired private SeckillService seckillService;

    @MockitoBean private RabbitTemplate rabbitTemplate;

    @Test
    void confirm_ack时置为已确认且不补偿() {
        Long orderNo = seckillService.seckill(USER_ID, GOODS_ID);

        captureCorrelationData().getFuture().complete(new CorrelationData.Confirm(true, null));

        assertThat(statusOf(orderNo)).isEqualTo(SeckillOrderStatus.CONFIRMED.name());
        assertThat(stockLeft()).isEqualTo("49");
        assertThat(isOrdered()).isTrue();
    }

    @Test
    void confirm_nack时不做断言并保持待确认() {
        Long orderNo = seckillService.seckill(USER_ID, GOODS_ID);

        // 连接断开时框架会为所有未确认消息补发 nack，它无法证明消息未入队，因此不得补偿
        captureCorrelationData()
                .getFuture()
                .complete(new CorrelationData.Confirm(false, "Channel closed"));

        assertThat(statusOf(orderNo)).isEqualTo(SeckillOrderStatus.PENDING.name());
        assertThat(stockLeft()).isEqualTo("49");
        assertThat(isOrdered()).isTrue();
    }

    @Test
    void 消息无法路由时立即补偿() {
        Long orderNo = seckillService.seckill(USER_ID, GOODS_ID);

        CorrelationData correlationData = captureCorrelationData();
        // 退回先于确认到达，且随附的通常是 ack（broker 收到了但无处可投）
        correlationData.setReturned(
                new ReturnedMessage(
                        new Message(new byte[0]),
                        312,
                        "NO_ROUTE",
                        RabbitMQConfiguration.SECKILL_EXCHANGE,
                        RabbitMQConfiguration.SECKILL_ROUTING_KEY));
        correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));

        assertThat(statusOf(orderNo)).isEqualTo(SeckillOrderStatus.FAILED.name());
        assertThat(stockLeft()).isEqualTo("50");
        assertThat(isOrdered()).isFalse();
    }

    // ---- 辅助 ----

    /** 捕获 seckill 投递时使用的 CorrelationData，供测试手动完成其 future。 */
    private CorrelationData captureCorrelationData() {
        ArgumentCaptor<CorrelationData> captor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate)
                .convertAndSend(
                        eq(RabbitMQConfiguration.SECKILL_EXCHANGE),
                        eq(RabbitMQConfiguration.SECKILL_ROUTING_KEY),
                        any(Map.class),
                        captor.capture());
        return captor.getValue();
    }

    private String statusOf(Long orderNo) {
        return (String) stringRedisTemplate.opsForHash().get(ORDER_KEY_PREFIX + orderNo, "status");
    }

    private String stockLeft() {
        return stringRedisTemplate.opsForValue().get(STOCK_KEY);
    }

    private boolean isOrdered() {
        return Boolean.TRUE.equals(
                stringRedisTemplate.opsForSet().isMember(ORDERED_KEY, String.valueOf(USER_ID)));
    }
}
