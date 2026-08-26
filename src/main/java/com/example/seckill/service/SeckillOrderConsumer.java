package com.example.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.seckill.common.SeckillOrderStatus;
import com.example.seckill.entity.Goods;
import com.example.seckill.entity.Order;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.fault.FailpointService;
import com.example.seckill.fault.Failpoints;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.config.RabbitMqConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀订单 MQ 消费者，落库与死信补偿。
 *
 * @author jiyunhe
 */

@Slf4j
@Component
public class SeckillOrderConsumer {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private SeckillGoodsService seckillGoodsService;
    @Autowired
    private GoodsService goodsService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private FailpointService failpointService;

    private static final RedisScript<Long> COMPENSATE_LUA;
    private static final RedisScript<Long> CALIBRATE_LUA;

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

        DefaultRedisScript<Long> calibrateScript = new DefaultRedisScript<>();
        calibrateScript.setResultType(Long.class);
        calibrateScript.setScriptText(
                "redis.call('set', KEYS[1], ARGV[2])\n" +
                "redis.call('srem', KEYS[2], ARGV[1])\n" +
                "redis.call('hset', KEYS[3], 'status', 'FAILED', 'updatedAt', ARGV[3])\n" +
                "redis.call('expire', KEYS[3], 86400)\n" +
                "return 1\n"
        );
        CALIBRATE_LUA = calibrateScript;
    }

    /**
     * 消费秒杀订单队列消息，异步落库创建订单并扣减数据库库存。
     * 先按订单号做幂等检查，再校验秒杀商品与关联商品是否存在，插入订单后
     * 通过乐观锁扣减库存。
     *
     * @param msg 消息体，包含 userId、seckillGoodsId、orderNo 三个键
     * @throws RuntimeException 秒杀商品或关联商品不存在、库存不足时抛出，触发 MQ 重试
     */
    @RabbitListener(queues = RabbitMqConfig.SECKILL_QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void handleSeckillOrder(Map<String, Object> msg) {
        Long userId = Long.valueOf(msg.get("userId").toString());
        Long seckillGoodsId = Long.valueOf(msg.get("seckillGoodsId").toString());
        Long orderNo = ((Number) msg.get("orderNo")).longValue();
        String startTime = (String) msg.get("startTime");

        // 终态检查：已补偿(FAILED)或已成功(CONSUMED)的交易禁止消费复活，直接幂等 ACK
        Object statusObj = stringRedisTemplate.opsForHash().get("seckill:order:" + orderNo, "status");
        if (statusObj != null) {
            SeckillOrderStatus status = SeckillOrderStatus.valueOf(statusObj.toString());
            if (status == SeckillOrderStatus.FAILED || status == SeckillOrderStatus.CONSUMED) {
                return;
            }
        }

        // 幂等性检查
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        if (count > 0) {
            return;
        }
        failpointService.block(Failpoints.INSERT_BEFORE);

        SeckillGoods seckillGoods = seckillGoodsService.getById(seckillGoodsId);
        if (seckillGoods == null) {
            throw new RuntimeException("秒杀商品不存在");
        }
        Goods goods = goodsService.getById(seckillGoods.getGoodsId());
        if (goods == null) {
            throw new RuntimeException("关联商品不存在");
        }

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setGoodsId(seckillGoods.getGoodsId());
        order.setSeckillGoodsId(seckillGoodsId);
        order.setGoodsName(goods.getName());
        order.setGoodsPrice(seckillGoods.getSeckillPrice());
        order.setQuantity(1);
        order.setTotalAmount(seckillGoods.getSeckillPrice());
        order.setStatus(0);
        orderMapper.insert(order);

        boolean updated = seckillGoodsService.update(new LambdaUpdateWrapper<SeckillGoods>()
                .eq(SeckillGoods::getId, seckillGoodsId)
                .ge(SeckillGoods::getSeckillStock, 1)
                .setSql("seckill_stock = seckill_stock - 1"));
        if (!updated) {
            // 业务失败：DB 库存不足（Redis/DB 漂移），就地校准而非 incr，避免制造假库存
            calibrateRedisStock(seckillGoodsId, startTime, userId, orderNo);
            throw new AmqpRejectAndDontRequeueException("库存不足，已校准 Redis");
        }

        // 事务提交成功后清除订单预占状态；超时残留由后续对账任务兜底
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                stringRedisTemplate.opsForHash().put("seckill:order:" + orderNo, "status", SeckillOrderStatus.CONSUMED.name());
                stringRedisTemplate.opsForHash().put("seckill:order:" + orderNo, "updatedAt", String.valueOf(System.currentTimeMillis()));
                stringRedisTemplate.expire("seckill:order:" + orderNo, SeckillOrderStatus.FINAL_TTL_SECONDS, TimeUnit.SECONDS);
                failpointService.block(Failpoints.COMMIT_AFTER);
            }
        });
    }

    /**
     * 业务失败校准：DB 库存不足说明 Redis 与 DB 已漂移，将 Redis 库存同步为 DB 真实值，
     * 释放占位并标记失败，避免继续 incr 制造假库存。
     */
    private void calibrateRedisStock(Long seckillGoodsId, String startTime, Long userId, Long orderNo) {
        SeckillGoods latest = seckillGoodsService.getById(seckillGoodsId);
        int dbStock = latest != null ? latest.getSeckillStock() : 0;
        stringRedisTemplate.execute(CALIBRATE_LUA,
                Arrays.asList("seckill:stock:" + seckillGoodsId + ":" + startTime,
                              "seckill:ordered:" + seckillGoodsId + ":" + startTime,
                              "seckill:order:" + orderNo),
                userId.toString(), String.valueOf(dbStock), String.valueOf(System.currentTimeMillis()));
    }

    /**
     * 消费死信队列消息，对扣减成功的 Redis 库存与已下单记录执行补偿：
     * 通过 Lua 脚本原子地恢复 Redis 库存并将用户移出已下单集合，保证数据最终一致。
     *
     * @param msg 消息体，包含 userId、seckillGoodsId、orderNo、startTime（活动版本）四个键
     */
    @RabbitListener(queues = RabbitMqConfig.SECKILL_DLQ)
    public void handleDeadLetter(Map<String, Object> msg) {
        Long userId = Long.valueOf(msg.get("userId").toString());
        Long seckillGoodsId = Long.valueOf(msg.get("seckillGoodsId").toString());
        Long orderNo = ((Number) msg.get("orderNo")).longValue();

        String startTime = (String) msg.get("startTime");

        log.warn("订单 {} 进入死信队列，执行补偿", orderNo);
        stringRedisTemplate.execute(COMPENSATE_LUA,
                Arrays.asList("seckill:stock:" + seckillGoodsId + ":" + startTime,
                              "seckill:ordered:" + seckillGoodsId + ":" + startTime,
                              "seckill:order:" + orderNo),
                userId.toString(), String.valueOf(System.currentTimeMillis()));
        failpointService.block(Failpoints.COMPENSATE_AFTER);
    }
}
