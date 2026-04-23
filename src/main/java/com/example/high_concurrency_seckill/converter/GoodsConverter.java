package com.example.high_concurrency_seckill.converter;

import com.example.high_concurrency_seckill.entity.Goods;
import com.example.high_concurrency_seckill.vo.GoodsVO;

public class GoodsConverter {
    public static GoodsVO toVO(Goods goods) {
        if (goods == null) {
            return null;
        }
        GoodsVO vo = new GoodsVO();
        vo.setId(goods.getId());
        vo.setName(goods.getName());
        vo.setPrice(goods.getPrice());
        vo.setStock(goods.getStock());
        vo.setDetail(goods.getDetail());
        vo.setCreateTime(goods.getCreateTime());
        return vo;
    }
}
