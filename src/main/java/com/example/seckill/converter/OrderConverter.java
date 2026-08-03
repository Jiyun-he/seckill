package com.example.seckill.converter;

import com.example.seckill.entity.Order;
import com.example.seckill.vo.OrderVO;

public class OrderConverter {
    public static OrderVO toVO(Order order) {
        if (order == null) {
            return null;
        }
        OrderVO vo = new OrderVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setUserId(order.getUserId());
        vo.setGoodsId(order.getGoodsId());
        vo.setGoodsName(order.getGoodsName());
        vo.setGoodsPrice(order.getGoodsPrice());
        vo.setQuantity(order.getQuantity());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setStatus(order.getStatus());
        vo.setCreateTime(order.getCreateTime());
        return vo;
    }
}
