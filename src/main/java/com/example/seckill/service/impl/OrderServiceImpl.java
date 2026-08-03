package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.seckill.converter.OrderConverter;
import com.example.seckill.entity.Goods;
import com.example.seckill.entity.Order;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.service.GoodsService;
import com.example.seckill.service.OrderService;
import com.example.seckill.vo.OrderVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.seckill.util.SnowflakeIdUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

    private Long generateOrderNo() {
        return SnowflakeIdUtil.nextId();
    }
}
