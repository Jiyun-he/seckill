package com.example.high_concurrency_seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.high_concurrency_seckill.entity.Order;
import com.example.high_concurrency_seckill.vo.OrderVO;

public interface OrderService extends IService<Order> {
    OrderVO createOrder(Long userId, Long goodsId, Integer quantity);
}
