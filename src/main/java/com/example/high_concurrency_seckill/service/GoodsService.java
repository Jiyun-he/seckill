package com.example.high_concurrency_seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.high_concurrency_seckill.entity.Goods;
import com.example.high_concurrency_seckill.vo.GoodsVO;

public interface GoodsService extends IService<Goods> {
    Page<GoodsVO> listGoods(Integer page, Integer size, String keyword);

    GoodsVO getGoodsDetail(Long id);
}
