package com.example.high_concurrency_seckill.converter;

import com.example.high_concurrency_seckill.entity.SeckillGoods;
import com.example.high_concurrency_seckill.vo.SeckillGoodsVO;

public class SeckillGoodsConverter {
    public static SeckillGoodsVO toVO(SeckillGoods seckillGoods) {
        if (seckillGoods == null) {
            return null;
        }
        SeckillGoodsVO vo = new SeckillGoodsVO();
        vo.setId(seckillGoods.getId());
        vo.setGoodsId(seckillGoods.getGoodsId());
        vo.setSeckillPrice(seckillGoods.getSeckillPrice());
        vo.setSeckillStock(seckillGoods.getSeckillStock());
        vo.setStartTime(seckillGoods.getStartTime());
        vo.setEndTime(seckillGoods.getEndTime());
        return vo;
    }
}
