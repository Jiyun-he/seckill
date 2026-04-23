package com.example.high_concurrency_seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.high_concurrency_seckill.entity.SeckillGoods;

public interface SeckillGoodsService extends IService<SeckillGoods> {
    // 可以添加自定义方法，比如扣减库存等，这里继承IService已经够用
}