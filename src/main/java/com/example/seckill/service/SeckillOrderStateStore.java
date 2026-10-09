package com.example.seckill.service;

import com.example.seckill.common.SeckillOrderStatus;

import lombok.RequiredArgsConstructor;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;

/**
 * 秒杀订单 Redis 状态与库存原子操作。
 *
 * <p>所有状态转换都显式校验来源状态：中间态只能进入终态一次，FAILED 与 CONSUMED
 * 不能互相覆盖；库存补偿与 FAILED 转换在同一个 Lua 脚本中完成。</p>
 *
 * @author jiyunhe
 */
@Component
@RequiredArgsConstructor
public class SeckillOrderStateStore {

    public static final long APPLIED = 1L;
    public static final long IDEMPOTENT = 0L;
    public static final long TERMINAL_CONFLICT = -1L;
    public static final long INVALID_STATE = -2L;

    private static final String ORDER_KEY_PREFIX = "seckill:order:";
    private static final String STOCK_KEY_PREFIX = "seckill:stock:";
    private static final String ORDERED_KEY_PREFIX = "seckill:ordered:";

    private static final RedisScript<Long> CONFIRM_LUA;
    private static final RedisScript<Long> CONSUME_LUA;
    private static final RedisScript<Long> COMPENSATE_LUA;
    private static final RedisScript<Long> CALIBRATE_LUA;

    static {
        DefaultRedisScript<Long> confirmScript = new DefaultRedisScript<>();
        confirmScript.setResultType(Long.class);
        confirmScript.setScriptText(
                "local status = redis.call('hget', KEYS[1], 'status')\n"
                        + "if status == 'CONFIRMED' then return 0 end\n"
                        + "if status == 'FAILED' or status == 'CONSUMED' then return -1 end\n"
                        + "if status ~= 'PENDING' then return -2 end\n"
                        + "redis.call('hset', KEYS[1], 'status', 'CONFIRMED', 'updatedAt', ARGV[1])\n"
                        + "redis.call('expire', KEYS[1], ARGV[2])\n"
                        + "return 1\n");
        CONFIRM_LUA = confirmScript;

        DefaultRedisScript<Long> consumeScript = new DefaultRedisScript<>();
        consumeScript.setResultType(Long.class);
        consumeScript.setScriptText(
                "local status = redis.call('hget', KEYS[1], 'status')\n"
                        + "if status == 'CONSUMED' then return 0 end\n"
                        + "if status == 'FAILED' then return -1 end\n"
                        + "if status ~= 'PENDING' and status ~= 'CONFIRMED' then return -2 end\n"
                        + "redis.call('hset', KEYS[1], 'status', 'CONSUMED', 'updatedAt', ARGV[1])\n"
                        + "redis.call('expire', KEYS[1], ARGV[2])\n"
                        + "return 1\n");
        CONSUME_LUA = consumeScript;

        DefaultRedisScript<Long> compensateScript = new DefaultRedisScript<>();
        compensateScript.setResultType(Long.class);
        compensateScript.setScriptText(
                "local status = redis.call('hget', KEYS[3], 'status')\n"
                        + "if status == 'FAILED' then return 0 end\n"
                        + "if status == 'CONSUMED' then return -1 end\n"
                        + "if status ~= 'PENDING' and status ~= 'CONFIRMED' then return -2 end\n"
                        + "redis.call('incr', KEYS[1])\n"
                        + "redis.call('srem', KEYS[2], ARGV[1])\n"
                        + "redis.call('hset', KEYS[3], 'status', 'FAILED', 'updatedAt', ARGV[2])\n"
                        + "redis.call('expire', KEYS[3], ARGV[3])\n"
                        + "return 1\n");
        COMPENSATE_LUA = compensateScript;

        DefaultRedisScript<Long> calibrateScript = new DefaultRedisScript<>();
        calibrateScript.setResultType(Long.class);
        calibrateScript.setScriptText(
                "local status = redis.call('hget', KEYS[3], 'status')\n"
                        + "if status == 'FAILED' then return 0 end\n"
                        + "if status == 'CONSUMED' then return -1 end\n"
                        + "if status ~= 'PENDING' and status ~= 'CONFIRMED' then return -2 end\n"
                        + "redis.call('set', KEYS[1], ARGV[2])\n"
                        + "redis.call('srem', KEYS[2], ARGV[1])\n"
                        + "redis.call('hset', KEYS[3], 'status', 'FAILED', 'updatedAt', ARGV[3])\n"
                        + "redis.call('expire', KEYS[3], ARGV[4])\n"
                        + "return 1\n");
        CALIBRATE_LUA = calibrateScript;
    }

    private final StringRedisTemplate stringRedisTemplate;

    public String getStatus(Long orderNo) {
        Object value = stringRedisTemplate.opsForHash().get(orderKey(orderNo), "status");
        return value != null ? value.toString() : null;
    }

    public long markConfirmed(Long orderNo) {
        return executeStatusScript(
                CONFIRM_LUA, orderNo, SeckillOrderStatus.INTERMEDIATE_TTL_SECONDS);
    }

    public long markConsumed(Long orderNo) {
        return executeStatusScript(CONSUME_LUA, orderNo, SeckillOrderStatus.FINAL_TTL_SECONDS);
    }

    public long compensate(Long userId, Long seckillGoodsId, String startTime, Long orderNo) {
        Long result =
                stringRedisTemplate.execute(
                        COMPENSATE_LUA,
                        Arrays.asList(
                                stockKey(seckillGoodsId, startTime),
                                orderedKey(seckillGoodsId, startTime),
                                orderKey(orderNo)),
                        userId.toString(),
                        String.valueOf(System.currentTimeMillis()),
                        String.valueOf(SeckillOrderStatus.FINAL_TTL_SECONDS));
        return result != null ? result : INVALID_STATE;
    }

    public long calibrate(
            Long userId, Long seckillGoodsId, String startTime, Long orderNo, int databaseStock) {
        Long result =
                stringRedisTemplate.execute(
                        CALIBRATE_LUA,
                        Arrays.asList(
                                stockKey(seckillGoodsId, startTime),
                                orderedKey(seckillGoodsId, startTime),
                                orderKey(orderNo)),
                        userId.toString(),
                        String.valueOf(databaseStock),
                        String.valueOf(System.currentTimeMillis()),
                        String.valueOf(SeckillOrderStatus.FINAL_TTL_SECONDS));
        return result != null ? result : INVALID_STATE;
    }

    private long executeStatusScript(RedisScript<Long> script, Long orderNo, long ttlSeconds) {
        Long result =
                stringRedisTemplate.execute(
                        script,
                        Collections.singletonList(orderKey(orderNo)),
                        String.valueOf(System.currentTimeMillis()),
                        String.valueOf(ttlSeconds));
        return result != null ? result : INVALID_STATE;
    }

    private String orderKey(Long orderNo) {
        return ORDER_KEY_PREFIX + orderNo;
    }

    private String stockKey(Long seckillGoodsId, String startTime) {
        return STOCK_KEY_PREFIX + seckillGoodsId + ":" + startTime;
    }

    private String orderedKey(Long seckillGoodsId, String startTime) {
        return ORDERED_KEY_PREFIX + seckillGoodsId + ":" + startTime;
    }
}
