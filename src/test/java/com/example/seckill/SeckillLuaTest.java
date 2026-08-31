package com.example.seckill;

import com.example.seckill.common.BusinessException;
import com.example.seckill.common.SeckillOrderStatus;
import com.example.seckill.service.SeckillOrderConsumer;
import com.example.seckill.service.SeckillService;
import com.example.seckill.service.impl.SeckillServiceImpl;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 秒杀入口 Lua 原子预占 + 补偿幂等测试（#1~#7）。
 *
 * <p>验证核心不变量：扣库存、一人一单、活动时间窗口校验、订单预占状态建立、
 * 补偿库存恢复，以及补偿的幂等性。mock {@link RabbitTemplate} 使成功路径不真正投递 MQ，
 * 保证断言确定（预占状态停留在 PENDING）。</p>
 *
 * @author jiyunhe
 */
@SpringBootTest(classes = SeckillTestApplication.class)
class SeckillLuaTest extends AbstractIntegrationTest {

    private static final long USER_ID = 2001L;
    private static final long GOODS_ID = 1L;
    /** 种子秒杀商品 id=1 的活动版本（startTime=2026-01-01 00:00:00） */
    private static final String VERSION = "20260101000000";
    private static final String STOCK_KEY = "seckill:stock:1:20260101000000";
    private static final String ORDERED_KEY = "seckill:ordered:1:20260101000000";
    private static final String ACTIVITY_KEY = "seckill:activity:1";

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private SeckillOrderConsumer consumer;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    // ---- 预占 ----

    @Test
    void seckill_正常预占() {
        Long orderNo = seckillService.seckill(USER_ID, GOODS_ID);

        assertThat(orderNo).isNotNull();
        // 库存 -1
        assertThat(stringRedisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("49");
        // 用户加入一人一单集合
        assertThat(stringRedisTemplate.opsForSet().isMember(ORDERED_KEY, String.valueOf(USER_ID))).isTrue();
        // 订单预占状态建立（对外合并为 PROCESSING）
        assertThat(seckillService.getSeckillOrderStatus(orderNo).getStatus()).isEqualTo(SeckillOrderStatus.PROCESSING);
    }

    @Test
    void seckill_同一用户重复请求被拒绝() {
        seckillService.seckill(USER_ID, GOODS_ID);

        // 第二次请求被拒绝，且不再次扣库存、不建立第二份预占
        assertBusinessError(HttpStatus.CONFLICT, "已参与过该秒杀",
                () -> seckillService.seckill(USER_ID, GOODS_ID));

        assertThat(stringRedisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("49");
        assertThat(stringRedisTemplate.opsForSet().size(ORDERED_KEY)).isEqualTo(1L);
    }

    @Test
    void seckill_库存为0被拒绝() {
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "0");

        assertBusinessError(HttpStatus.CONFLICT, "库存不足",
                () -> seckillService.seckill(USER_ID, GOODS_ID));

        // 库存不变、不占位、不建立订单
        assertThat(stringRedisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("0");
        assertThat(stringRedisTemplate.opsForSet().isMember(ORDERED_KEY, String.valueOf(USER_ID))).isFalse();
    }

    @Test
    void seckill_活动未开始被拒绝() {
        long now = System.currentTimeMillis();
        setActivity(VERSION, now + 3600_000L, now + 7200_000L);

        assertBusinessError(HttpStatus.FORBIDDEN, "不在秒杀时间段内",
                () -> seckillService.seckill(USER_ID, GOODS_ID));

        // Redis 完全不变
        assertThat(stringRedisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("50");
        assertThat(stringRedisTemplate.opsForSet().isMember(ORDERED_KEY, String.valueOf(USER_ID))).isFalse();
    }

    @Test
    void seckill_活动已结束被拒绝() {
        long now = System.currentTimeMillis();
        setActivity(VERSION, now - 7200_000L, now - 3600_000L);

        assertBusinessError(HttpStatus.FORBIDDEN, "不在秒杀时间段内",
                () -> seckillService.seckill(USER_ID, GOODS_ID));

        assertThat(stringRedisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("50");
        assertThat(stringRedisTemplate.opsForSet().isMember(ORDERED_KEY, String.valueOf(USER_ID))).isFalse();
    }

    // ---- 补偿幂等 ----

    @Test
    void compensate_补偿正常恢复库存() {
        long orderNo = 999L;
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "10");
        stringRedisTemplate.opsForSet().add(ORDERED_KEY, String.valueOf(USER_ID));
        seedOrder(orderNo, SeckillOrderStatus.PENDING);

        consumer.handleDeadLetter(deadLetterMsg(orderNo));

        assertThat(stringRedisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("11");
        assertThat(stringRedisTemplate.opsForSet().isMember(ORDERED_KEY, String.valueOf(USER_ID))).isFalse();
        assertThat(statusOf(orderNo)).isEqualTo("FAILED");
    }

    @Test
    void compensate_重复补偿只生效一次() {
        long orderNo = 999L;
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "10");
        stringRedisTemplate.opsForSet().add(ORDERED_KEY, String.valueOf(USER_ID));
        seedOrder(orderNo, SeckillOrderStatus.PENDING);

        consumer.handleDeadLetter(deadLetterMsg(orderNo));
        consumer.handleDeadLetter(deadLetterMsg(orderNo));

        // 库存只恢复一次
        assertThat(stringRedisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("11");
        assertThat(stringRedisTemplate.opsForSet().isMember(ORDERED_KEY, String.valueOf(USER_ID))).isFalse();
        assertThat(statusOf(orderNo)).isEqualTo("FAILED");
    }

    // ---- 状态机终态保护（竞态修复回归） ----

    @Test
    void 中间态PENDING正常升级为CONFIRMED() throws Exception {
        long orderNo = 778L;
        String orderKey = "seckill:order:" + orderNo;
        stringRedisTemplate.opsForHash().put(orderKey, "status", "PENDING");

        Long result = executeUpdateStatusLua(orderKey, "CONFIRMED");

        assertThat(result).isEqualTo(1L);
        assertThat(statusOf(orderNo)).isEqualTo("CONFIRMED");
    }

    @Test
    void 终态CONSUMED不被晚到confirm覆盖() throws Exception {
        long orderNo = 779L;
        String orderKey = "seckill:order:" + orderNo;
        stringRedisTemplate.opsForHash().put(orderKey, "status", "CONSUMED");

        Long result = executeUpdateStatusLua(orderKey, "CONFIRMED");

        // 终态具有更高权威：返回 0，状态不被覆盖
        assertThat(result).isEqualTo(0L);
        assertThat(statusOf(orderNo)).isEqualTo("CONSUMED");
    }

    @Test
    void 终态FAILED不被晚到超时覆盖() throws Exception {
        long orderNo = 780L;
        String orderKey = "seckill:order:" + orderNo;
        stringRedisTemplate.opsForHash().put(orderKey, "status", "FAILED");

        Long result = executeUpdateStatusLua(orderKey, "RETRY");

        assertThat(result).isEqualTo(0L);
        assertThat(statusOf(orderNo)).isEqualTo("FAILED");
    }

    // ---- 辅助 ----

    private Long executeUpdateStatusLua(String orderKey, String newStatus) throws Exception {
        Field field = SeckillServiceImpl.class.getDeclaredField("UPDATE_STATUS_LUA");
        field.setAccessible(true);
        RedisScript<Long> script = (RedisScript<Long>) field.get(null);
        return stringRedisTemplate.execute(script, Collections.singletonList(orderKey),
                newStatus, String.valueOf(System.currentTimeMillis()), "3600");
    }

    private void setActivity(String version, long startMillis, long endMillis) {
        stringRedisTemplate.opsForValue().set(ACTIVITY_KEY, version + "|" + startMillis + "|" + endMillis);
    }

    private void seedOrder(long orderNo, SeckillOrderStatus status) {
        String key = "seckill:order:" + orderNo;
        Map<String, String> fields = new HashMap<>();
        fields.put("status", status.name());
        fields.put("userId", String.valueOf(USER_ID));
        fields.put("seckillGoodsId", String.valueOf(GOODS_ID));
        fields.put("startTime", VERSION);
        fields.put("retryCount", "0");
        fields.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        stringRedisTemplate.opsForHash().putAll(key, fields);
    }

    private String statusOf(long orderNo) {
        return (String) stringRedisTemplate.opsForHash().get("seckill:order:" + orderNo, "status");
    }

    private Map<String, Object> deadLetterMsg(long orderNo) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("userId", USER_ID);
        msg.put("seckillGoodsId", GOODS_ID);
        msg.put("orderNo", orderNo);
        msg.put("startTime", VERSION);
        return msg;
    }

    private void assertBusinessError(HttpStatus status, String msgContains, ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getStatus()).isEqualTo(status);
                    assertThat(be.getMessage()).contains(msgContains);
                });
    }
}
