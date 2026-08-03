package com.example.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.vo.SeckillGoodsVO;

public interface SeckillService extends IService<SeckillGoods> {
    void loadSeckillStockToRedis();  // 预热库存到Redis
    Long seckill(Long userId, Long seckillGoodsId);  // 秒杀下单，返回订单号

    SeckillGoodsVO getSeckillGoodsDetail(Long id);
}
