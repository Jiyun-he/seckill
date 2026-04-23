package com.example.high_concurrency_seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.high_concurrency_seckill.entity.SeckillGoods;
import com.example.high_concurrency_seckill.vo.SeckillGoodsVO;

public interface SeckillService extends IService<SeckillGoods> {
    void loadSeckillStockToRedis();  // 预热库存到Redis
    String seckill(Long userId, Long seckillGoodsId);  // 秒杀下单

    SeckillGoodsVO getSeckillGoodsDetail(Long id);
}
