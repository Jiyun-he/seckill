package com.example.high_concurrency_seckill.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.high_concurrency_seckill.config.RabbitMQConfig;
import com.example.high_concurrency_seckill.converter.SeckillGoodsConverter;
import com.example.high_concurrency_seckill.entity.SeckillGoods;
import com.example.high_concurrency_seckill.mapper.SeckillGoodsMapper;
import com.example.high_concurrency_seckill.service.SeckillService;
import com.example.high_concurrency_seckill.vo.SeckillGoodsVO;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SeckillServiceImpl extends ServiceImpl<SeckillGoodsMapper, SeckillGoods> implements SeckillService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    private static final String SECKILL_STOCK_KEY = "seckill:stock:";
    private static final String SECKILL_GOODS_CACHE_KEY = "seckill:goods:";
    private static final String SECKILL_ORDERED_SET_KEY = "seckill:ordered:";
    private static final RedisScript<Long> DECR_STOCK_LUA;

    static {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText(
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
        DECR_STOCK_LUA = script;
    }

    @PostConstruct  // 项目启动时自动预热
    @Override
    public void loadSeckillStockToRedis() {
        List<SeckillGoods> list = this.list();
        for (SeckillGoods sg : list) {
            String key = SECKILL_STOCK_KEY + sg.getId();
            // 只在 key 不存在时设置，避免重启覆盖已变更的 Redis 库存
            Boolean absent = stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(sg.getSeckillStock()));
            if (Boolean.TRUE.equals(absent)) {
                log.info("预热秒杀商品ID：" + sg.getId() + "，库存：" + sg.getSeckillStock());
            } else {
                log.info("秒杀商品ID：" + sg.getId() + " Redis 库存已存在，跳过预热");
            }
        }
    }

    @Override
    public SeckillGoodsVO getSeckillGoodsDetail(Long id) {
        String cacheKey = SECKILL_GOODS_CACHE_KEY + id;
        SeckillGoods cached = (SeckillGoods) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            if (cached.getId() == null) {
                return null;
            }
            SeckillGoodsVO vo = SeckillGoodsConverter.toVO(cached);
            String stockStr = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + id);
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
        String stockStr = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + id);
        if (stockStr != null) {
            vo.setSeckillStock(Integer.parseInt(stockStr));
        }
        return vo;
    }

    @Override
    public String seckill(Long userId, Long seckillGoodsId) {
        // 1. 校验秒杀时间段（从数据库查商品信息?）
        SeckillGoods seckillGoods = this.getById(seckillGoodsId);
        if (seckillGoods == null) {
            throw new RuntimeException("秒杀商品不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(seckillGoods.getStartTime()) || now.isAfter(seckillGoods.getEndTime())) {
            throw new RuntimeException("不在秒杀时间段内");
        }

        // 2. 分布式锁：防止同一用户重复秒杀（锁key包含userId和商品Id）
        String lockKey = "seckill:lock:" + userId + ":" + seckillGoodsId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // 尝试加锁，最多等待3秒，锁自动释放时间10秒（避免死锁）
            boolean locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new RuntimeException("请勿重复下单");
            }

            // 3. 一人一单检查：Redis Set 持久化记录已下单用户
            String orderedKey = SECKILL_ORDERED_SET_KEY + seckillGoodsId;
            if (Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(orderedKey, userId.toString()))) {
                throw new RuntimeException("已参与过该秒杀");
            }

            // 4. Redis Lua 脚本原子扣减库存
            String stockKey = SECKILL_STOCK_KEY + seckillGoodsId;
            Long stock = stringRedisTemplate.execute(DECR_STOCK_LUA, Collections.singletonList(stockKey));
            if (stock == null) {
                throw new RuntimeException("系统繁忙");
            }
            if (stock == -2L) {
                throw new RuntimeException("库存未初始化");
            }
            if (stock == -3L) {
                throw new RuntimeException("库存数据异常");
            }
            if (stock < 0) {
                throw new RuntimeException("库存不足");
            }

            // 5. 扣减成功，生成订单号，发送消息到MQ
            String orderNo = generateOrderNo();
            sendSeckillOrderMessage(userId, seckillGoodsId, orderNo);
            stringRedisTemplate.opsForSet().add(orderedKey, userId.toString());
            log.info("秒杀成功，生成订单号：" + orderNo + "，用户：" + userId);

            return orderNo;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("系统繁忙");
        } finally {
            // 确保释放锁（只有当前线程持有的锁才释放）
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String generateOrderNo() {
        // 简单生成：时间戳+随机数（实际可用雪花算法）

        return "SECKILL" + System.currentTimeMillis() + (int)(Math.random() * 1000);
    }

    private void sendSeckillOrderMessage(Long userId, Long seckillGoodsId, String orderNo) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("userId", userId);
        msg.put("seckillGoodsId", seckillGoodsId);
        msg.put("orderNo", orderNo);

        String stockKey = SECKILL_STOCK_KEY + seckillGoodsId;
        String orderedKey = SECKILL_ORDERED_SET_KEY + seckillGoodsId;
        String userIdStr = userId.toString();

        CorrelationData correlationData = new CorrelationData(orderNo);
        correlationData.getFuture().thenAccept(confirm -> {
            if (!confirm.isAck()) {
                retrySend(msg, stockKey, orderedKey, userIdStr, 0);
            }
        });

        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.SECKILL_EXCHANGE,
                    RabbitMQConfig.SECKILL_ROUTING_KEY, msg, correlationData);
        } catch (Exception e) {
            compensateRedis(stockKey, orderedKey, userIdStr);
            throw new RuntimeException("消息发送失败", e);
        }
    }

    private void retrySend(Map<String, Object> msg, String stockKey,
                           String orderedKey, String userIdStr, int attempt) {
        if (attempt >= 3) {
            compensateRedis(stockKey, orderedKey, userIdStr);
            log.warn("MQ 重试 3 次均失败，已补偿 Redis");
            return;
        }
        long delay = (long) Math.pow(2, attempt) * 1000;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        CorrelationData newCd = new CorrelationData(String.valueOf(msg.get("orderNo")));
        newCd.getFuture().thenAccept(confirm -> {
            if (!confirm.isAck()) {
                retrySend(msg, stockKey, orderedKey, userIdStr, attempt + 1);
            }
        });
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.SECKILL_EXCHANGE,
                    RabbitMQConfig.SECKILL_ROUTING_KEY, msg, newCd);
        } catch (Exception e) {
            retrySend(msg, stockKey, orderedKey, userIdStr, attempt + 1);
        }
    }

    private void compensateRedis(String stockKey, String orderedKey, String userIdStr) {
        stringRedisTemplate.opsForValue().increment(stockKey);
        stringRedisTemplate.opsForSet().remove(orderedKey, userIdStr);
    }
}
