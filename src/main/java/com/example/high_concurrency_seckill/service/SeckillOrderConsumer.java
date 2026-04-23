package com.example.high_concurrency_seckill.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.high_concurrency_seckill.entity.Goods;
import com.example.high_concurrency_seckill.entity.Order;
import com.example.high_concurrency_seckill.entity.SeckillGoods;
import com.example.high_concurrency_seckill.mapper.OrderMapper;
import com.example.high_concurrency_seckill.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
public class SeckillOrderConsumer {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private SeckillGoodsService seckillGoodsService; // 你需要创建这个Service
    @Autowired
    private GoodsService goodsService; // 普通商品Service

    @RabbitListener(queues = RabbitMQConfig.SECKILL_QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void handleSeckillOrder(Map<String, Object> msg) {
        Long userId = Long.valueOf(msg.get("userId").toString());
        Long seckillGoodsId = Long.valueOf(msg.get("seckillGoodsId").toString());
        String orderNo = msg.get("orderNo").toString();

        // 幂等性检查：根据订单号查询是否已存在（避免重复消费）
        Long count = orderMapper.selectCount(new LambdaUpdateWrapper<Order>().eq(Order::getOrderNo, orderNo));
        if (count > 0) {
            return; // 已处理过
        }

        // 查询秒杀商品信息
        SeckillGoods seckillGoods = seckillGoodsService.getById(seckillGoodsId);
        if (seckillGoods == null) {
            throw new RuntimeException("秒杀商品不存在");
        }
        // 查询普通商品详情（获取名称等快照）
        Goods goods = goodsService.getById(seckillGoods.getGoodsId());

        // 创建订单
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setGoodsId(seckillGoods.getGoodsId());
        order.setGoodsName(goods.getName());
        order.setGoodsPrice(seckillGoods.getSeckillPrice());
        order.setQuantity(1);
        order.setTotalAmount(seckillGoods.getSeckillPrice());
        order.setStatus(0); // 0-待支付
        orderMapper.insert(order);

        // 扣减数据库中的秒杀库存（最终一致性）
        seckillGoodsService.update(new LambdaUpdateWrapper<SeckillGoods>()
                .eq(SeckillGoods::getId, seckillGoodsId)
                .ge(SeckillGoods::getSeckillStock, 1)
                .setSql("seckill_stock = seckill_stock - 1"));
    }
}