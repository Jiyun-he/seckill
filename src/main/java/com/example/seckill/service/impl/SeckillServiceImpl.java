package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.seckill.common.BusinessException;
import com.example.seckill.config.RabbitMqConfig;
import com.example.seckill.converter.SeckillGoodsConverter;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.mapper.SeckillGoodsMapper;
import com.example.seckill.service.SeckillService;
import com.example.seckill.vo.SeckillGoodsVO;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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
import java.time.format.DateTimeFormatter;
import java.util.Collections;
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
    private RedissonClient redissonClient;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    private static final String SECKILL_STOCK_KEY = "seckill:stock:";
    private static final String SECKILL_GOODS_CACHE_KEY = "seckill:goods:";
    private static final String SECKILL_ORDERED_SET_KEY = "seckill:ordered:";
    /** 活动版本格式：startTime 暂代版本标识（活动配置冻结后不可变） */
    private static final DateTimeFormatter ACTIVITY_VERSION_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** Lua 扣减脚本返回码：库存 key 不存在 */
    private static final long STOCK_NOT_INIT = -2L;
    /** Lua 扣减脚本返回码：库存值非数字 */
    private static final long STOCK_DATA_ERROR = -3L;
    private static final RedisScript<Long> DECR_STOCK_LUA;
    private static final RedisScript<Long> COMPENSATE_LUA;

    static {
        DefaultRedisScript<Long> decrScript = new DefaultRedisScript<>();
        decrScript.setResultType(Long.class);
        decrScript.setScriptText(
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
                "return redis.call('decr', KEYS[1])\n"
        );
        DECR_STOCK_LUA = decrScript;

        DefaultRedisScript<Long> compensateScript = new DefaultRedisScript<>();
        compensateScript.setResultType(Long.class);
        compensateScript.setScriptText(
                "redis.call('incr', KEYS[1])\n" +
                "redis.call('srem', KEYS[2], ARGV[1])\n" +
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
     * 生成带活动版本的一人一单 key：seckill:ordered:{id}:{startTime}。
     */
    private String buildOrderedKey(Long seckillGoodsId, LocalDateTime startTime) {
        return SECKILL_ORDERED_SET_KEY + seckillGoodsId + ":" + startTime.format(ACTIVITY_VERSION_FORMATTER);
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
        // 1. 校验秒杀时间段（从数据库查商品信息?）
        SeckillGoods seckillGoods = this.getById(seckillGoodsId);
        if (seckillGoods == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "秒杀商品不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(seckillGoods.getStartTime()) || now.isAfter(seckillGoods.getEndTime())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "不在秒杀时间段内");
        }

        // 2. 分布式锁：防止同一用户重复秒杀（锁key包含userId和商品Id）
        String lockKey = "seckill:lock:" + userId + ":" + seckillGoodsId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // 尝试加锁，最多等待3秒，锁自动释放时间10秒（避免死锁）
            boolean locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new BusinessException(HttpStatus.CONFLICT, "请勿重复下单");
            }

            // 3. 一人一单检查：Redis Set 持久化记录已下单用户
            String orderedKey = buildOrderedKey(seckillGoodsId, seckillGoods.getStartTime());
            if (Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(orderedKey, userId.toString()))) {
                throw new BusinessException(HttpStatus.CONFLICT, "已参与过该秒杀");
            }

            // 4. Redis Lua 脚本原子扣减库存
            String stockKey = buildStockKey(seckillGoodsId, seckillGoods.getStartTime());
            Long stock = stringRedisTemplate.execute(DECR_STOCK_LUA, Collections.singletonList(stockKey));
            if (stock == null) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "系统繁忙");
            }
            if (stock == STOCK_NOT_INIT) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "库存未初始化");
            }
            if (stock == STOCK_DATA_ERROR) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "库存数据异常");
            }
            if (stock < 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "库存不足");
            }

            // 5. 扣减成功，生成订单号，发送消息到MQ
            Long orderNo = generateOrderNo();
            sendSeckillOrderMessage(userId, seckillGoods, orderNo);
            stringRedisTemplate.opsForSet().add(orderedKey, userId.toString());
            log.info("秒杀成功，生成订单号：{}，用户：{}", orderNo, userId);

            return orderNo;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "系统繁忙");
        } finally {
            // 确保释放锁（只有当前线程持有的锁才释放）
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Long generateOrderNo() {
        return snowflakeIdUtil.nextId();
    }

    private void sendSeckillOrderMessage(Long userId, SeckillGoods seckillGoods, Long orderNo) {
        Long seckillGoodsId = seckillGoods.getId();
        Map<String, Object> msg = new HashMap<>(16);
        msg.put("userId", userId);
        msg.put("seckillGoodsId", seckillGoodsId);
        msg.put("orderNo", orderNo);
        // startTime 作为活动版本标识，供死信消费者还原带版本的库存 key
        msg.put("startTime", seckillGoods.getStartTime().format(ACTIVITY_VERSION_FORMATTER));

        String stockKey = buildStockKey(seckillGoodsId, seckillGoods.getStartTime());
        String orderedKey = buildOrderedKey(seckillGoodsId, seckillGoods.getStartTime());
        String userIdStr = userId.toString();

        CorrelationData correlationData = new CorrelationData(orderNo.toString());
        correlationData.getFuture().thenAccept(confirm -> {
            if (!confirm.isAck()) {
                // confirm 未 ack，直接补偿；真正的重试由后续的消息状态 + 定时 retry job 完成
                compensateRedis(stockKey, orderedKey, userIdStr);
                log.warn("订单 {} MQ confirm 未 ack，已补偿 Redis", orderNo);
            }
        });

        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.SECKILL_EXCHANGE,
                    RabbitMqConfig.SECKILL_ROUTING_KEY, msg, correlationData);
        } catch (Exception e) {
            compensateRedis(stockKey, orderedKey, userIdStr);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "消息发送失败", e);
        }
    }

    private void compensateRedis(String stockKey, String orderedKey, String userIdStr) {
        stringRedisTemplate.execute(COMPENSATE_LUA,
                java.util.Arrays.asList(stockKey, orderedKey), userIdStr);
    }
}
