package com.example.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.seckill.entity.Order;
import com.example.seckill.vo.OrderVO;

/**
 * 订单服务接口。
 *
 * @author jiyunhe
 */

public interface OrderService extends IService<Order> {

    /**
     * 创建普通购买订单：校验商品存在性与库存，乐观锁扣减库存后生成订单
     * （订单号使用雪花算法），返回创建成功的订单信息。
     *
     * @param userId   下单用户 ID
     * @param goodsId  购买的商品 ID
     * @param quantity 购买数量，必须大于 0
     * @return 创建成功的订单 VO（初始状态为待支付）
     * @throws RuntimeException 商品不存在或库存不足时抛出
     */
    OrderVO createOrder(Long userId, Long goodsId, Integer quantity);
}
