package com.example.high_concurrency_seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.high_concurrency_seckill.entity.SeckillGoods;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SeckillGoodsMapper extends BaseMapper<SeckillGoods> {
}
