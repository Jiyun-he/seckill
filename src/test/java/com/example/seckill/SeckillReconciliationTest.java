package com.example.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.entity.Order;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.service.SeckillGoodsService;
import com.example.seckill.service.SeckillReconciliationScanner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 异常交易扫描与对账恢复测试（#13~#17）。
 *
 * <p>验证对账框架以 DB 为最终事实，将悬挂交易收敛到 SUCCESS/FAILED：DB 有单修正为 CONSUMED
 * 且绝不补偿、无单未耗尽重投、无单耗尽幂等补偿、终态不重复处理，以及库存漂移按 DB 校准。
 * mock {@link RabbitTemplate} 以拦截重投、避免真实投递。</p>
 *
 * @author jiyunhe
 */
@SpringBootTest(classes = SeckillTestApplication.class)
class SeckillReconciliationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 2001L;
    private static final long GOODS_ID = 1L;
    private static final String VERSION = "20260101000000";
    private static final String STOCK_KEY = "seckill:stock:1:20260101000000";
    private static final String ORDERED_KEY = "seckill:ordered:1:20260101000000";
    /** 极旧的 updatedAt，确保被视为悬挂（超过 120s 阈值） */
    private static final long STALE_UPDATED_AT = 0L;

    @Autowired
    private SeckillReconciliationScanner scanner;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private SeckillGoodsService seckillGoodsService;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Test
    void reconcile_悬挂且DB已有订单修正为已消费且不补偿() {
        long orderNo = 92001L;
        insertOrder(orderNo);
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "10");
        stringRedisTemplate.opsForSet().add(ORDERED_KEY, String.valueOf(USER_ID));
        seedOrder(orderNo, "PENDING", STALE_UPDATED_AT, 0);

        scanner.scanReconcile();

        assertThat(statusOf(orderNo)).isEqualTo("CONSUMED");
        // 绝不补偿：库存不变、占位不释放
        assertThat(stringRedisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("10");
        assertThat(stringRedisTemplate.opsForSet().isMember(ORDERED_KEY, String.valueOf(USER_ID))).isTrue();
    }

    @Test
    void reconcile_悬挂且无订单未耗尽时重投并增加重试次数() {
        long orderNo = 92002L;
        seedOrder(orderNo, "PENDING", STALE_UPDATED_AT, 0);

        scanner.scanReconcile();

        assertThat(retryCountOf(orderNo)).isEqualTo("1");
        verify(rabbitTemplate, times(1)).convertAndSend(
                ArgumentMatchers.eq("seckill.exchange"),
                ArgumentMatchers.eq("seckill.order"),
                ArgumentMatchers.any(Map.class),
                ArgumentMatchers.any(CorrelationData.class));
    }

    @Test
    void reconcile_悬挂且无订单重试耗尽时执行补偿() {
        long orderNo = 92003L;
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "10");
        stringRedisTemplate.opsForSet().add(ORDERED_KEY, String.valueOf(USER_ID));
        seedOrder(orderNo, "RETRY", STALE_UPDATED_AT, 3);

        scanner.scanReconcile();

        assertThat(stringRedisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("11");
        assertThat(stringRedisTemplate.opsForSet().isMember(ORDERED_KEY, String.valueOf(USER_ID))).isFalse();
        assertThat(statusOf(orderNo)).isEqualTo("FAILED");
    }

    @Test
    void reconcile_终态订单被扫描时不重复处理() {
        long failedNo = 92004L;
        long consumedNo = 92005L;
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "10");
        seedOrder(failedNo, "FAILED", STALE_UPDATED_AT, 0);
        seedOrder(consumedNo, "CONSUMED", STALE_UPDATED_AT, 0);

        scanner.scanReconcile();

        assertThat(statusOf(failedNo)).isEqualTo("FAILED");
        assertThat(statusOf(consumedNo)).isEqualTo("CONSUMED");
        assertThat(stringRedisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("10");
        verify(rabbitTemplate, never()).convertAndSend(
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(Map.class), ArgumentMatchers.any(CorrelationData.class));
    }

    @Test
    void reconcileStock_Redis超过DB时按DB校准() {
        // Redis 库存 100 超过 DB 库存 50，且无活跃预占
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "100");

        scanner.reconcileStock();

        assertThat(stringRedisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("50");
    }

    // ---- 辅助 ----

    private void insertOrder(long orderNo) {
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(USER_ID);
        order.setGoodsId(1001L);
        order.setSeckillGoodsId(GOODS_ID);
        order.setGoodsName("Product 1");
        order.setGoodsPrice(new BigDecimal("99.99"));
        order.setQuantity(1);
        order.setTotalAmount(new BigDecimal("99.99"));
        order.setStatus(0);
        orderMapper.insert(order);
    }

    private void seedOrder(long orderNo, String status, long updatedAt, int retryCount) {
        Map<String, String> fields = new HashMap<>();
        fields.put("status", status);
        fields.put("userId", String.valueOf(USER_ID));
        fields.put("seckillGoodsId", String.valueOf(GOODS_ID));
        fields.put("startTime", VERSION);
        fields.put("retryCount", String.valueOf(retryCount));
        fields.put("updatedAt", String.valueOf(updatedAt));
        stringRedisTemplate.opsForHash().putAll("seckill:order:" + orderNo, fields);
    }

    private String statusOf(long orderNo) {
        return (String) stringRedisTemplate.opsForHash().get("seckill:order:" + orderNo, "status");
    }

    private String retryCountOf(long orderNo) {
        return (String) stringRedisTemplate.opsForHash().get("seckill:order:" + orderNo, "retryCount");
    }
}
