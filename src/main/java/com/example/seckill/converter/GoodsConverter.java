package com.example.seckill.converter;

import com.example.seckill.entity.Goods;
import com.example.seckill.vo.GoodsVO;

/**
 * 商品实体与视图对象转换器。
 *
 * @author jiyunhe
 */

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
