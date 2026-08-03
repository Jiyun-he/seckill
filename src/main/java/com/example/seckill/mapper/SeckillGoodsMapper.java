package com.example.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.seckill.entity.SeckillGoods;
import org.apache.ibatis.annotations.Mapper;

/**
 * 秒杀商品 Mapper。
 *
 * @author jiyunhe
 */

@Mapper
public interface SeckillGoodsMapper extends BaseMapper<SeckillGoods> {
}
