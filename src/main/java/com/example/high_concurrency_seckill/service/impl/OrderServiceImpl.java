package com.example.high_concurrency_seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.high_concurrency_seckill.converter.OrderConverter;
import com.example.high_concurrency_seckill.entity.Goods;
import com.example.high_concurrency_seckill.entity.Order;
import com.example.high_concurrency_seckill.mapper.OrderMapper;
import com.example.high_concurrency_seckill.service.GoodsService;
import com.example.high_concurrency_seckill.service.OrderService;
import com.example.high_concurrency_seckill.vo.OrderVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {

    @Autowired
    private GoodsService goodsService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO createOrder(Long userId, Long goodsId, Integer quantity) {
        // 1. 查询商品（加锁？简单做法不加，后续秒杀会加）
        Goods goods = goodsService.getById(goodsId);
        if (goods == null) {
            throw new RuntimeException("商品不存在");
        }
        if (goods.getStock() < quantity) {
            throw new RuntimeException("库存不足");
        }

        // 2. 扣减库存（使用乐观锁，避免超卖）
        boolean updated = goodsService.update(new LambdaUpdateWrapper<Goods>()
                .eq(Goods::getId, goodsId)
                .ge(Goods::getStock, quantity)
                .setSql("stock = stock - " + quantity));
        if (!updated) {
            throw new RuntimeException("库存不足");
        }

        // 3. 生成订单
        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setGoodsId(goodsId);
        order.setGoodsName(goods.getName());
        order.setGoodsPrice(goods.getPrice());
        order.setQuantity(quantity);
        order.setTotalAmount(goods.getPrice().multiply(new BigDecimal(quantity)));
        order.setStatus(0); // 待支付
        this.save(order);

        return OrderConverter.toVO(order);
    }

    private String generateOrderNo() {
        // 简单生成：时间戳+随机数，实际可使用雪花算法
        return System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 8);
    }
}
