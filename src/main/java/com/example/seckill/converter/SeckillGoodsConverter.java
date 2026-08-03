package com.example.seckill.converter;

import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.vo.SeckillGoodsVO;

/**
 * 秒杀商品实体与视图对象转换器。
 *
 * @author jiyunhe
 */

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
