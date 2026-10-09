package com.example.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.entity.Order;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.fault.FailpointService;
import com.example.seckill.fault.Failpoints;
import com.example.seckill.fault.FaultInjectionException;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.SeckillGoodsMapper;
import com.example.seckill.service.SeckillCompensationService;
import com.example.seckill.service.SeckillOrderConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 消费事务与最终补偿使用同一商品行锁的确定性竞态测试。 */
@ActiveProfiles("fault-test")
@SpringBootTest(classes = SeckillTestApplication.class)
class SeckillCompensationConcurrencyTest extends AbstractIntegrationTest {

    private static final long USER_ID = 2001L;
    private static final long GOODS_ID = 1L;
    private static final String VERSION = "20260101000000";
    private static final String STOCK_KEY = "seckill:stock:1:20260101000000";
    private static final String ORDERED_KEY = "seckill:ordered:1:20260101000000";

    @Autowired private SeckillOrderConsumer consumer;
    @Autowired private SeckillCompensationService compensationService;
    @Autowired private FailpointService failpointService;
    @Autowired private OrderMapper orderMapper;
    @Autowired private SeckillGoodsMapper seckillGoodsMapper;

    @AfterEach
    void releaseFailpoints() {
        failpointService.release(Failpoints.CONSUME_LOCKED);
        failpointService.release(Failpoints.COMPENSATE_LOCKED);
        failpointService.release(Failpoints.COMMIT_BEFORE_STATUS);
    }

    @Test
    void 消费者先持锁时补偿等待提交并禁止归还库存() throws Exception {
        long orderNo = 93001L;
        seedReservation(orderNo);
        failpointService.enableBlock(Failpoints.CONSUME_LOCKED);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> consume = pool.submit(() -> consumer.handleSeckillOrder(msg(orderNo)));
            awaitBlocked(Failpoints.CONSUME_LOCKED);

            Future<SeckillCompensationService.Result> compensate =
                    pool.submit(
                            () ->
                                    compensationService.compensateIfNoOrder(
                                            USER_ID,
                                            GOODS_ID,
                                            VERSION,
                                            orderNo,
                                            "TEST_CONSUMER_FIRST"));

            failpointService.release(Failpoints.CONSUME_LOCKED);
            consume.get(10, TimeUnit.SECONDS);

            assertThat(compensate.get(10, TimeUnit.SECONDS))
                    .isEqualTo(SeckillCompensationService.Result.ORDER_EXISTS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(countOrders(orderNo)).isEqualTo(1L);
        assertThat(databaseStock()).isEqualTo(49);
        assertThat(redisStock()).isEqualTo("49");
        assertThat(statusOf(orderNo)).isEqualTo("CONSUMED");
        assertThat(isOrdered()).isTrue();
    }

    @Test
    void 补偿先持锁时晚到消费者看到FAILED后禁止落库() throws Exception {
        long orderNo = 93002L;
        seedReservation(orderNo);
        failpointService.enableBlock(Failpoints.COMPENSATE_LOCKED);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SeckillCompensationService.Result> compensate =
                    pool.submit(
                            () ->
                                    compensationService.compensateIfNoOrder(
                                            USER_ID,
                                            GOODS_ID,
                                            VERSION,
                                            orderNo,
                                            "TEST_COMPENSATION_FIRST"));
            awaitBlocked(Failpoints.COMPENSATE_LOCKED);

            Future<?> consume = pool.submit(() -> consumer.handleSeckillOrder(msg(orderNo)));
            failpointService.release(Failpoints.COMPENSATE_LOCKED);

            assertThat(compensate.get(10, TimeUnit.SECONDS))
                    .isEqualTo(SeckillCompensationService.Result.COMPENSATED);
            consume.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(countOrders(orderNo)).isZero();
        assertThat(databaseStock()).isEqualTo(50);
        assertThat(redisStock()).isEqualTo("50");
        assertThat(statusOf(orderNo)).isEqualTo("FAILED");
        assertThat(isOrdered()).isFalse();
    }

    @Test
    void SQL提交后状态写回前补偿看到订单并禁止归还库存() throws Exception {
        long orderNo = 93003L;
        seedReservation(orderNo);
        failpointService.enableBlock(Failpoints.COMMIT_BEFORE_STATUS);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> consume = pool.submit(() -> consumer.handleSeckillOrder(msg(orderNo)));
            awaitBlocked(Failpoints.COMMIT_BEFORE_STATUS);

            assertThat(
                            compensationService.compensateIfNoOrder(
                                    USER_ID, GOODS_ID, VERSION, orderNo, "TEST_AFTER_COMMIT_GAP"))
                    .isEqualTo(SeckillCompensationService.Result.ORDER_EXISTS);

            failpointService.release(Failpoints.COMMIT_BEFORE_STATUS);
            consume.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(countOrders(orderNo)).isEqualTo(1L);
        assertThat(databaseStock()).isEqualTo(49);
        assertThat(redisStock()).isEqualTo("49");
        assertThat(statusOf(orderNo)).isEqualTo("CONSUMED");
    }

    @Test
    void SQL提交后状态写回崩溃可由补偿裁决恢复且不归还库存() {
        long orderNo = 93004L;
        seedReservation(orderNo);
        failpointService.enableThrow(Failpoints.COMMIT_BEFORE_STATUS);

        assertThatThrownBy(() -> consumer.handleSeckillOrder(msg(orderNo)))
                .isInstanceOf(FaultInjectionException.class);

        assertThat(countOrders(orderNo)).isEqualTo(1L);
        assertThat(statusOf(orderNo)).isEqualTo("PENDING");

        failpointService.disable(Failpoints.COMMIT_BEFORE_STATUS);
        assertThat(
                        compensationService.compensateIfNoOrder(
                                USER_ID, GOODS_ID, VERSION, orderNo, "TEST_CRASH_RECOVERY"))
                .isEqualTo(SeckillCompensationService.Result.ORDER_EXISTS);

        assertThat(databaseStock()).isEqualTo(49);
        assertThat(redisStock()).isEqualTo("49");
        assertThat(statusOf(orderNo)).isEqualTo("CONSUMED");
    }

    private void seedReservation(long orderNo) {
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "49");
        stringRedisTemplate.opsForSet().add(ORDERED_KEY, String.valueOf(USER_ID));
        Map<String, String> fields = new HashMap<>();
        fields.put("status", "PENDING");
        fields.put("userId", String.valueOf(USER_ID));
        fields.put("seckillGoodsId", String.valueOf(GOODS_ID));
        fields.put("startTime", VERSION);
        fields.put("retryCount", "3");
        fields.put("updatedAt", "0");
        stringRedisTemplate.opsForHash().putAll("seckill:order:" + orderNo, fields);
    }

    private Map<String, Object> msg(long orderNo) {
        Map<String, Object> message = new HashMap<>();
        message.put("userId", USER_ID);
        message.put("seckillGoodsId", GOODS_ID);
        message.put("orderNo", orderNo);
        message.put("startTime", VERSION);
        return message;
    }

    private void awaitBlocked(String failpoint) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Object blocked = failpointService.status(failpoint).get("blockedThreads");
            if (blocked instanceof Number number && number.intValue() > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("failpoint 未在 10 秒内命中：" + failpoint);
    }

    private long countOrders(long orderNo) {
        return orderMapper.selectCount(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
    }

    private int databaseStock() {
        SeckillGoods goods = seckillGoodsMapper.selectById(GOODS_ID);
        return goods.getSeckillStock();
    }

    private String redisStock() {
        return stringRedisTemplate.opsForValue().get(STOCK_KEY);
    }

    private String statusOf(long orderNo) {
        return (String) stringRedisTemplate.opsForHash().get("seckill:order:" + orderNo, "status");
    }

    private boolean isOrdered() {
        return Boolean.TRUE.equals(
                stringRedisTemplate.opsForSet().isMember(ORDERED_KEY, String.valueOf(USER_ID)));
    }
}
