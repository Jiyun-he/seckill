package com.example.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.entity.Order;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.service.SeckillGoodsService;
import com.example.seckill.service.SeckillOrderConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 秒杀订单 MQ 消费与数据库事务测试（#8~#11）。
 *
 * <p>验证核心数据库不变量：一个 orderNo 最多产生一条订单、DB 库存最多扣一次、
 * 事务不留下半完成状态。直接调用 {@code handleSeckillOrder}（绕过 MQ 投递），
 * 事务代理与 {@code afterCommit} 同步执行，保证断言确定。</p>
 *
 * @author jiyunhe
 */
@SpringBootTest(classes = SeckillTestApplication.class)
class SeckillOrderConsumerTest extends AbstractIntegrationTest {

    private static final long USER_ID = 2001L;
    private static final long GOODS_ID = 1L;
    private static final String VERSION = "20260101000000";
    private static final String STOCK_KEY = "seckill:stock:1:20260101000000";
    private static final String ORDERED_KEY = "seckill:ordered:1:20260101000000";

    @Autowired
    private SeckillOrderConsumer consumer;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private SeckillGoodsService seckillGoodsService;

    @Test
    void consume_正常消费落库并扣减库存() {
        long orderNo = 90001L;

        consumer.handleSeckillOrder(msg(orderNo));

        assertThat(countOrders(orderNo)).isEqualTo(1L);
        assertThat(dbStock()).isEqualTo(49);
        assertThat(statusOf(orderNo)).isEqualTo("CONSUMED");
    }

    @Test
    void consume_同一orderNo重复消费只落库一次() {
        long orderNo = 90002L;

        consumer.handleSeckillOrder(msg(orderNo));
        consumer.handleSeckillOrder(msg(orderNo));

        assertThat(countOrders(orderNo)).isEqualTo(1L);
        assertThat(dbStock()).isEqualTo(49);
    }

    @Test
    void consume_并发重复消费仍只有一条订单() throws Exception {
        long orderNo = 90003L;
        Map<String, Object> message = msg(orderNo);
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        consumer.handleSeckillOrder(message);
                    } catch (Exception ignored) {
                        // 并发下第二个线程可能命中唯一索引冲突或幂等 return，最终以 DB 状态为准
                    }
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(countOrders(orderNo)).isEqualTo(1L);
        assertThat(dbStock()).isEqualTo(49);
    }

    @Test
    void consume_DB库存不足时漂移校准() {
        long orderNo = 90004L;
        // 模拟 Redis/DB 漂移：Redis 预占成功（库存 1）但 DB 已无库存
        setDbStock(0);
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "1");
        seedOrder(orderNo, "PENDING");

        assertThatThrownBy(() -> consumer.handleSeckillOrder(msg(orderNo)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        // 订单未落库（异常导致事务回滚）
        assertThat(countOrders(orderNo)).isZero();
        // DB 库存未扣减
        assertThat(dbStock()).isZero();
        // Redis 校准为 DB 真实值、释放占位、标记失败
        assertThat(stringRedisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("0");
        assertThat(statusOf(orderNo)).isEqualTo("FAILED");
        assertThat(stringRedisTemplate.opsForSet().isMember(ORDERED_KEY, String.valueOf(USER_ID))).isFalse();
    }

    // ---- 辅助 ----

    private Map<String, Object> msg(long orderNo) {
        Map<String, Object> m = new HashMap<>();
        m.put("userId", USER_ID);
        m.put("seckillGoodsId", GOODS_ID);
        m.put("orderNo", orderNo);
        m.put("startTime", VERSION);
        return m;
    }

    private void seedOrder(long orderNo, String status) {
        Map<String, String> fields = new HashMap<>();
        fields.put("status", status);
        fields.put("userId", String.valueOf(USER_ID));
        fields.put("seckillGoodsId", String.valueOf(GOODS_ID));
        fields.put("startTime", VERSION);
        fields.put("retryCount", "0");
        fields.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        stringRedisTemplate.opsForHash().putAll("seckill:order:" + orderNo, fields);
    }

    private String statusOf(long orderNo) {
        return (String) stringRedisTemplate.opsForHash().get("seckill:order:" + orderNo, "status");
    }

    private long countOrders(long orderNo) {
        return orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
    }

    private int dbStock() {
        return seckillGoodsService.getById(GOODS_ID).getSeckillStock();
    }

    private void setDbStock(int stock) {
        SeckillGoods sg = seckillGoodsService.getById(GOODS_ID);
        sg.setSeckillStock(stock);
        seckillGoodsService.updateById(sg);
    }
}
