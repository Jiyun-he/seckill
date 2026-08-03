package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.seckill.converter.GoodsConverter;
import com.example.seckill.entity.Goods;
import com.example.seckill.mapper.GoodsMapper;
import com.example.seckill.service.GoodsService;
import com.example.seckill.vo.GoodsVO;
import org.springframework.stereotype.Service;

/**
 * 商品服务实现。
 *
 * @author jiyunhe
 */

@Service
public class GoodsServiceImpl extends ServiceImpl<GoodsMapper, Goods> implements GoodsService {
    @Override
    public Page<GoodsVO> listGoods(Integer page, Integer size, String keyword) {
        Page<Goods> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<Goods> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like(Goods::getName, keyword);
        }
        wrapper.orderByDesc(Goods::getCreateTime);
        Page<Goods> result = this.page(pageObj, wrapper);

        Page<GoodsVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(GoodsConverter::toVO).toList());
        return voPage;
    }

    @Override
    public GoodsVO getGoodsDetail(Long id) {
        return GoodsConverter.toVO(this.getById(id));
    }
}
