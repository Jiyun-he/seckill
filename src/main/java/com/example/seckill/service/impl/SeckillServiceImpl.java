package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.seckill.common.BusinessException;
import com.example.seckill.common.SeckillOrderStatus;
import com.example.seckill.config.RabbitMqConfig;
import com.example.seckill.converter.SeckillGoodsConverter;
import com.example.seckill.entity.Order;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.fault.FailpointService;
import com.example.seckill.fault.Failpoints;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.SeckillGoodsMapper;
import com.example.seckill.service.SeckillService;
import com.example.seckill.vo.SeckillGoodsVO;
import com.example.seckill.vo.SeckillOrderStatusVO;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import com.example.seckill.util.SnowflakeIdUtil;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀服务实现。
 *
 * @author jiyunhe
 */

@Slf4j
@Service
public class SeckillServiceImpl extends ServiceImpl<SeckillGoodsMapper, SeckillGoods> implements SeckillService {

    @Autowired
    private RedisTemplate<String, SeckillGoods> redisTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;
    @Autowired
    private FailpointService failpointService;
    @Autowired
    private OrderMapper orderMapper;

    private static final String SECKILL_STOCK_KEY = "seckill:stock:";
    private static final String SECKILL_GOODS_CACHE_KEY = "seckill:goods:";
    private static final String SECKILL_ORDERED_SET_KEY = "seckill:ordered:";
    /** 活动时间段 key 前缀，value 为 startTimeStr|startMillis|endMillis */
    private static final String SECKILL_ACTIVITY_KEY = "seckill:activity:";
    /** 订单预占状态 key 前缀，value 为 PROCESSING，供后续对账追溯 */
    private static final String SECKILL_ORDER_PREOCCUPY_KEY = "seckill:order:";
    /** 活动版本格式：startTime 暂代版本标识（活动配置冻结后不可变） */
    private static final DateTimeFormatter ACTIVITY_VERSION_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** Lua 返回码：不在秒杀时间段内 */
    private static final long RETURN_NOT_IN_TIME = -5L;
    /** Lua 返回码：已参与过该秒杀 */
    private static final long RETURN_DUPLICATE = -4L;
    /** Lua 返回码：库存 key 不存在 */
    private static final long RETURN_STOCK_NOT_INIT = -2L;
    /** Lua 返回码：库存值非数字 */
    private static final long RETURN_STOCK_DATA_ERROR = -3L;
    /** Lua 返回码：库存不足 */
    private static final long RETURN_STOCK_EMPTY = -1L;
    private static final RedisScript<Long> SECKILL_LUA;
    private static final RedisScript<Long> COMPENSATE_LUA;

    static {
        DefaultRedisScript<Long> seckillScript = new DefaultRedisScript<>();
        seckillScript.setResultType(Long.class);
        seckillScript.setScriptText(
                "local now = tonumber(ARGV[3])\n" +
                "local start = tonumber(ARGV[4])\n" +
                "local stop = tonumber(ARGV[5])\n" +
                "if now < start or now > stop then\n" +
                "  return -5\n" +
                "end\n" +
                "if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then\n" +
                "  return -4\n" +
                "end\n" +
                "local stock = redis.call('get', KEYS[1])\n" +
                "if not stock then\n" +
                "  return -2\n" +
                "end\n" +
                "stock = tonumber(stock)\n" +
                "if not stock then\n" +
                "  return -3\n" +
                "end\n" +
                "if stock <= 0 then\n" +
                "  return -1\n" +
                "end\n" +
                "local after = redis.call('decr', KEYS[1])\n" +
                "redis.call('sadd', KEYS[2], ARGV[1])\n" +
                // 预占状态 PENDING TTL 3600s，后续流转 CONFIRMED/RETRY/FAILED/CONSUMED；超时残留由对账框架兜底
                "redis.call('set', KEYS[3], 'PENDING', 'EX', 3600)\n" +
                "return after\n"
        );
        SECKILL_LUA = seckillScript;

        DefaultRedisScript<Long> compensateScript = new DefaultRedisScript<>();
        compensateScript.setResultType(Long.class);
        compensateScript.setScriptText(
                "local status = redis.call('get', KEYS[3])\n" +
                "if status == 'FAILED' or status == 'CONSUMED' then\n" +
                "  return 0\n" +
                "end\n" +
                "redis.call('incr', KEYS[1])\n" +
                "redis.call('srem', KEYS[2], ARGV[1])\n" +
                "redis.call('set', KEYS[3], 'FAILED', 'EX', 86400)\n" +
                "return 1\n"
        );
        COMPENSATE_LUA = compensateScript;
    }

    /**
     * 生成带活动版本的库存 key：seckill:stock:{id}:{startTime}。
     * startTime 在此暂代活动版本，活动配置冻结后不可变，从而隔离不同场次的库存。
     */
    private String buildStockKey(Long seckillGoodsId, LocalDateTime startTime) {
        return SECKILL_STOCK_KEY + seckillGoodsId + ":" + startTime.format(ACTIVITY_VERSION_FORMATTER);
    }

    /**
     * LocalDateTime 转 epoch 毫秒（系统默认时区），供 Lua 内与当前时间比较。
     */
    private long toEpochMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 项目启动时自动预热。
     */
    @PostConstruct
    @Override
    public void loadSeckillStockToRedis() {
        List<SeckillGoods> list = this.list();
        for (SeckillGoods sg : list) {
            String key = buildStockKey(sg.getId(), sg.getStartTime());
            // 只在 key 不存在时设置，避免重启覆盖已变更的 Redis 库存
            Boolean absent = stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(sg.getSeckillStock()));
            if (Boolean.TRUE.equals(absent)) {
                log.info("预热秒杀商品ID：" + sg.getId() + "，库存：" + sg.getSeckillStock());
            } else {
                log.info("秒杀商品ID：" + sg.getId() + " Redis 库存已存在，跳过预热");
            }
            // 清理旧版（无活动版本）的库存与一人一单 key，避免历史残留
            stringRedisTemplate.delete(SECKILL_STOCK_KEY + sg.getId());
            stringRedisTemplate.delete(SECKILL_ORDERED_SET_KEY + sg.getId());

            // 预热活动时间段到 Redis，供秒杀入口在 Lua 中原子校验，避免热路径访问 MySQL
            String activityKey = SECKILL_ACTIVITY_KEY + sg.getId();
            String activityValue = sg.getStartTime().format(ACTIVITY_VERSION_FORMATTER)
                    + "|" + toEpochMillis(sg.getStartTime())
                    + "|" + toEpochMillis(sg.getEndTime());
            stringRedisTemplate.opsForValue().set(activityKey, activityValue);
        }
    }

    @Override
    public SeckillGoodsVO getSeckillGoodsDetail(Long id) {
        String cacheKey = SECKILL_GOODS_CACHE_KEY + id;
        SeckillGoods cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            if (cached.getId() == null) {
                return null;
            }
            SeckillGoodsVO vo = SeckillGoodsConverter.toVO(cached);
            String stockStr = stringRedisTemplate.opsForValue().get(buildStockKey(cached.getId(), cached.getStartTime()));
            if (stockStr != null) {
                vo.setSeckillStock(Integer.parseInt(stockStr));
            }
            return vo;
        }

        SeckillGoods seckillGoods = this.getById(id);
        if (seckillGoods == null) {
            SeckillGoods empty = new SeckillGoods();
            redisTemplate.opsForValue().set(cacheKey, empty, 60, TimeUnit.SECONDS);
            return null;
        }

        int expire = 300 + new Random().nextInt(300);
        redisTemplate.opsForValue().set(cacheKey, seckillGoods, expire, TimeUnit.SECONDS);
        SeckillGoodsVO vo = SeckillGoodsConverter.toVO(seckillGoods);
        // 从 Redis 读取实时库存覆盖缓存中的旧值
        String stockStr = stringRedisTemplate.opsForValue().get(buildStockKey(seckillGoods.getId(), seckillGoods.getStartTime()));
        if (stockStr != null) {
            vo.setSeckillStock(Integer.parseInt(stockStr));
        }
        return vo;
    }

    @Override
    public Long seckill(Long userId, Long seckillGoodsId) {
        // 1. 读取活动时间段（预热时已入 Redis，热路径不访问 MySQL）
        String activity = stringRedisTemplate.opsForValue().get(SECKILL_ACTIVITY_KEY + seckillGoodsId);
        if (activity == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "秒杀活动不存在");
        }
        // activity 格式：startTimeStr|startMillis|endMillis
        String[] parts = activity.split("\\|");
        String startTimeStr = parts[0];
        long startMillis = Long.parseLong(parts[1]);
        long endMillis = Long.parseLong(parts[2]);

        // 2. 先生成订单号，保证 Lua 内建立的预占状态有可追溯的 orderNo
        Long orderNo = generateOrderNo();

        // 3. 构建带活动版本的 key（startTime 作为版本标识，配置冻结后不可变）
        String stockKey = SECKILL_STOCK_KEY + seckillGoodsId + ":" + startTimeStr;
        String orderedKey = SECKILL_ORDERED_SET_KEY + seckillGoodsId + ":" + startTimeStr;
        String orderKey = SECKILL_ORDER_PREOCCUPY_KEY + orderNo;

        // 4. 单个 Lua 原子完成：校验时间段 + 一人一单 + 扣库存 + 占位 + 建立预占状态
        Long result = stringRedisTemplate.execute(SECKILL_LUA,
                Arrays.asList(stockKey, orderedKey, orderKey),
                userId.toString(), orderNo.toString(),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(startMillis), String.valueOf(endMillis));

        if (result == null) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "系统繁忙");
        }
        long code = result;
        if (code == RETURN_NOT_IN_TIME) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "不在秒杀时间段内");
        }
        if (code == RETURN_DUPLICATE) {
            throw new BusinessException(HttpStatus.CONFLICT, "已参与过该秒杀");
        }
        if (code == RETURN_STOCK_NOT_INIT) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "库存未初始化");
        }
        if (code == RETURN_STOCK_DATA_ERROR) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "库存数据异常");
        }
        if (code == RETURN_STOCK_EMPTY) {
            throw new BusinessException(HttpStatus.CONFLICT, "库存不足");
        }
        // code >= 0：扣减成功，剩余库存

        // 5. 扣减与占位成功，异步发 MQ 落库
        failpointService.block(Failpoints.PREOCCUPY_AFTER);
        sendSeckillOrderMessage(userId, seckillGoodsId, startTimeStr, orderNo);
        log.info("秒杀成功，生成订单号：{}，用户：{}", orderNo, userId);
        return orderNo;
    }

    private Long generateOrderNo() {
        return snowflakeIdUtil.nextId();
    }

    private void sendSeckillOrderMessage(Long userId, Long seckillGoodsId, String startTimeStr, Long orderNo) {
        Map<String, Object> msg = new HashMap<>(16);
        msg.put("userId", userId);
        msg.put("seckillGoodsId", seckillGoodsId);
        msg.put("orderNo", orderNo);
        // startTime 作为活动版本标识，供死信消费者还原带版本的库存 key
        msg.put("startTime", startTimeStr);

        String stockKey = SECKILL_STOCK_KEY + seckillGoodsId + ":" + startTimeStr;
        String orderedKey = SECKILL_ORDERED_SET_KEY + seckillGoodsId + ":" + startTimeStr;
        String orderKey = SECKILL_ORDER_PREOCCUPY_KEY + orderNo;
        String userIdStr = userId.toString();

        CorrelationData correlationData = new CorrelationData(orderNo.toString());

        // confirm 回调只更新状态：return 或 nack（明确失败）→ 即时幂等补偿；超时/结果未知 → RETRY；ack → CONFIRMED
        correlationData.getFuture().whenComplete((confirm, ex) -> {
            if (correlationData.getReturned() != null) {
                // 消息无法路由被退回（明确失败）
                compensateRedis(stockKey, orderedKey, orderKey, userIdStr);
                log.warn("订单 {} 消息无法路由，已补偿 Redis", orderNo);
            } else if (ex != null) {
                stringRedisTemplate.opsForValue().set(orderKey, SeckillOrderStatus.RETRY.name(),
                        SeckillOrderStatus.INTERMEDIATE_TTL_SECONDS, TimeUnit.SECONDS);
                log.warn("订单 {} confirm 超时/异常，标记 RETRY 等待对账", orderNo);
            } else if (confirm.isAck()) {
                stringRedisTemplate.opsForValue().set(orderKey, SeckillOrderStatus.CONFIRMED.name(),
                        SeckillOrderStatus.INTERMEDIATE_TTL_SECONDS, TimeUnit.SECONDS);
            } else {
                compensateRedis(stockKey, orderedKey, orderKey, userIdStr);
                log.warn("订单 {} confirm nack，已补偿 Redis", orderNo);
            }
        });

        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.SECKILL_EXCHANGE,
                    RabbitMqConfig.SECKILL_ROUTING_KEY, msg, correlationData);
            failpointService.block(Failpoints.PUBLISH_AFTER);
            failpointService.throwIfEnabled(Failpoints.PUBLISH_AFTER);
        } catch (Exception e) {
            compensateRedis(stockKey, orderedKey, orderKey, userIdStr);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "消息发送失败", e);
        }
    }

    private void compensateRedis(String stockKey, String orderedKey, String orderKey, String userIdStr) {
        stringRedisTemplate.execute(COMPENSATE_LUA,
                Arrays.asList(stockKey, orderedKey, orderKey), userIdStr);
    }

    @Override
    public SeckillOrderStatusVO getSeckillOrderStatus(Long orderNo) {
        String value = stringRedisTemplate.opsForValue().get(SECKILL_ORDER_PREOCCUPY_KEY + orderNo);
        SeckillOrderStatus status;
        if (value != null) {
            status = SeckillOrderStatus.valueOf(value).toUserVisible();
        } else {
            // Redis 状态已过期或从未写入，兜底查数据库订单是否已落库
            Long count = orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
            status = count != null && count > 0 ? SeckillOrderStatus.CONSUMED : SeckillOrderStatus.NOT_FOUND;
        }
        SeckillOrderStatusVO vo = new SeckillOrderStatusVO();
        vo.setOrderNo(orderNo);
        vo.setStatus(status);
        return vo;
    }
}
