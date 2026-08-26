package com.example.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.common.SeckillOrderStatus;
import com.example.seckill.entity.Order;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 异常交易扫描与对账恢复：独立于正常异常路径的兜底机制。
 *
 * <p>当正常异常处理自身都未执行完（如 confirm 超时、补偿中断、消费者崩溃），
 * 由本 Scanner 定时扫描中间态预占记录，以 MySQL 订单为最终业务事实，
 * 将悬挂交易收敛到 SUCCESS / FAILED，并做轻量库存对账。</p>
 *
 * @author jiyunhe
 */
@Slf4j
@Component
public class SeckillReconciliationScanner {

    private static final String ORDER_KEY_PREFIX = "seckill:order:";
    private static final String LOCK_KEY_PREFIX = "seckill:reconcile:lock:";
    private static final DateTimeFormatter VERSION_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** 中间态超时阈值：updatedAt 距今超过该值视为悬挂 */
    private static final long TIMEOUT_MS = 120_000L;
    /** 对账重投上限 */
    private static final int MAX_RETRY = 3;
    /** 抢占锁 TTL：防止实例崩溃后锁残留 */
    private static final long LOCK_TTL_SECONDS = 30L;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private SeckillService seckillService;
    @Autowired
    private SeckillGoodsService seckillGoodsService;

    private static final RedisScript<Long> COMPENSATE_LUA;

    static {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText(
                "local status = redis.call('hget', KEYS[3], 'status')\n" +
                "if status == 'FAILED' or status == 'CONSUMED' then\n" +
                "  return 0\n" +
                "end\n" +
                "redis.call('incr', KEYS[1])\n" +
                "redis.call('srem', KEYS[2], ARGV[1])\n" +
                "redis.call('hset', KEYS[3], 'status', 'FAILED', 'updatedAt', ARGV[2])\n" +
                "redis.call('expire', KEYS[3], 86400)\n" +
                "return 1\n"
        );
        COMPENSATE_LUA = script;
    }

    /**
     * 异常交易扫描：每隔 30s 扫描中间态预占记录，超时者按 DB 权威收敛。
     */
    @Scheduled(fixedDelay = 30_000)
    public void scanReconcile() {
        long now = System.currentTimeMillis();
        ScanOptions options = ScanOptions.scanOptions().match(ORDER_KEY_PREFIX + "*").count(100).build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Long orderNo = Long.parseLong(key.substring(ORDER_KEY_PREFIX.length()));
                try {
                    reconcileOne(orderNo, now);
                } catch (Exception e) {
                    log.error("对账处理订单 {} 异常", orderNo, e);
                }
            }
        }
    }

    private void reconcileOne(Long orderNo, long now) {
        String orderKey = ORDER_KEY_PREFIX + orderNo;
        // 安全抢占：两个实例不会同时恢复同一订单
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY_PREFIX + orderNo, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            Map<Object, Object> fields = stringRedisTemplate.opsForHash().entries(orderKey);
            if (fields == null || fields.isEmpty()) {
                return;
            }
            Object statusObj = fields.get("status");
            if (statusObj == null || !isIntermediate(statusObj.toString())) {
                return;
            }
            long updatedAt = Long.parseLong(fields.get("updatedAt").toString());
            if (updatedAt > now - TIMEOUT_MS) {
                return;
            }

            // DB 是最终业务事实
            Long count = orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
            if (count != null && count > 0) {
                // 订单已成功落库，仅修正状态，禁止补偿
                stringRedisTemplate.opsForHash().put(orderKey, "status", SeckillOrderStatus.CONSUMED.name());
                stringRedisTemplate.opsForHash().put(orderKey, "updatedAt", String.valueOf(now));
                log.info("对账：订单 {} 已落库，状态 {} -> CONSUMED", orderNo, statusObj);
                return;
            }

            // 无订单：有限重投，耗尽则补偿
            int retryCount = Integer.parseInt(String.valueOf(fields.getOrDefault("retryCount", "0")));
            Long userId = Long.valueOf(fields.get("userId").toString());
            Long seckillGoodsId = Long.valueOf(fields.get("seckillGoodsId").toString());
            String startTime = fields.get("startTime").toString();
            if (retryCount < MAX_RETRY) {
                stringRedisTemplate.opsForHash().increment(orderKey, "retryCount", 1);
                stringRedisTemplate.opsForHash().put(orderKey, "updatedAt", String.valueOf(now));
                seckillService.resendSeckillOrder(userId, seckillGoodsId, startTime, orderNo);
                log.info("对账：订单 {} 状态 {} 重投，retryCount={}", orderNo, statusObj, retryCount + 1);
            } else {
                stringRedisTemplate.execute(COMPENSATE_LUA,
                        Arrays.asList("seckill:stock:" + seckillGoodsId + ":" + startTime,
                                      "seckill:ordered:" + seckillGoodsId + ":" + startTime,
                                      orderKey),
                        userId.toString(), String.valueOf(now));
                log.info("对账：订单 {} 重试耗尽，补偿 FAILED", orderNo);
            }
        } finally {
            stringRedisTemplate.delete(LOCK_KEY_PREFIX + orderNo);
        }
    }

    private boolean isIntermediate(String status) {
        return SeckillOrderStatus.PENDING.name().equals(status)
                || SeckillOrderStatus.CONFIRMED.name().equals(status)
                || SeckillOrderStatus.RETRY.name().equals(status);
    }

    /**
     * 轻量库存对账：每隔 5 分钟抽查，Redis 库存超过 DB 可售时按 DB 校准，不做盲目增减。
     */
    @Scheduled(fixedDelay = 300_000)
    public void reconcileStock() {
        // 1. 统计每个商品的活跃预占数（中间态订单：Redis 已扣但 DB 未扣）
        Map<Long, Integer> activeByGoods = new HashMap<>();
        ScanOptions options = ScanOptions.scanOptions().match(ORDER_KEY_PREFIX + "*").count(100).build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Object statusObj = stringRedisTemplate.opsForHash().get(key, "status");
                if (statusObj == null || !isIntermediate(statusObj.toString())) {
                    continue;
                }
                Object goodsIdObj = stringRedisTemplate.opsForHash().get(key, "seckillGoodsId");
                if (goodsIdObj != null) {
                    activeByGoods.merge(Long.valueOf(goodsIdObj.toString()), 1, Integer::sum);
                }
            }
        }

        // 2. 期望 Redis = DB stock - active reservations，超卖侧校准
        List<SeckillGoods> goods = seckillGoodsService.list();
        for (SeckillGoods g : goods) {
            String stockKey = "seckill:stock:" + g.getId() + ":" + g.getStartTime().format(VERSION_FORMATTER);
            String redisStockStr = stringRedisTemplate.opsForValue().get(stockKey);
            if (redisStockStr == null) {
                continue;
            }
            int redisStock = Integer.parseInt(redisStockStr);
            int dbStock = g.getSeckillStock();
            int active = activeByGoods.getOrDefault(g.getId(), 0);
            int expected = dbStock - active;
            if (redisStock > expected) {
                log.warn("库存对账：商品 {} Redis={} > DB={} - active={} = {}，按期望值校准", g.getId(), redisStock, dbStock, active, expected);
                stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(Math.max(expected, 0)));
            }
        }
    }
}
