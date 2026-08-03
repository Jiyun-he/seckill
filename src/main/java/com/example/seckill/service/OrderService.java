package com.example.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.seckill.entity.Order;
import com.example.seckill.vo.OrderVO;

public interface OrderService extends IService<Order> {
    OrderVO createOrder(Long userId, Long goodsId, Integer quantity);
}
