package com.example.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.seckill.entity.Goods;
import com.example.seckill.entity.Order;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.config.RabbitMqConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Map;

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

    private static final RedisScript<Long> COMPENSATE_LUA;

    static {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText(
                "redis.call('incr', KEYS[1])\n" +
                "redis.call('srem', KEYS[2], ARGV[1])\n" +
                "return 1\n"
        );
        COMPENSATE_LUA = script;
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

        // 幂等性检查
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        if (count > 0) {
            return;
        }

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
            throw new RuntimeException("库存不足");
        }
    }

    /**
     * 消费死信队列消息，对扣减成功的 Redis 库存与已下单记录执行补偿：
     * 通过 Lua 脚本原子地恢复 Redis 库存并将用户移出已下单集合，保证数据最终一致。
     *
     * @param msg 消息体，包含 userId、seckillGoodsId、orderNo 三个键
     */
    @RabbitListener(queues = RabbitMqConfig.SECKILL_DLQ)
    public void handleDeadLetter(Map<String, Object> msg) {
        Long userId = Long.valueOf(msg.get("userId").toString());
        Long seckillGoodsId = Long.valueOf(msg.get("seckillGoodsId").toString());
        Long orderNo = ((Number) msg.get("orderNo")).longValue();

        log.warn("订单 {} 进入死信队列，执行补偿", orderNo);
        stringRedisTemplate.execute(COMPENSATE_LUA,
                Arrays.asList("seckill:stock:" + seckillGoodsId, "seckill:ordered:" + seckillGoodsId),
                userId.toString());
    }
}
