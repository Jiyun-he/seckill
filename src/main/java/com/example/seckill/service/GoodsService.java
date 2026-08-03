package com.example.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.seckill.entity.Goods;
import com.example.seckill.vo.GoodsVO;

public interface GoodsService extends IService<Goods> {
    Page<GoodsVO> listGoods(Integer page, Integer size, String keyword);

    GoodsVO getGoodsDetail(Long id);
}
