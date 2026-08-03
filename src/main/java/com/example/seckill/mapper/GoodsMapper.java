package com.example.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.seckill.entity.Goods;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品 Mapper。
 *
 * @author jiyunhe
 */

@Mapper
public interface GoodsMapper extends BaseMapper<Goods> {
}