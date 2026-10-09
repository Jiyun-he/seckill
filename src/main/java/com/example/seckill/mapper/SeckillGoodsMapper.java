package com.example.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.seckill.entity.SeckillGoods;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 秒杀商品 Mapper。
 *
 * @author jiyunhe
 */
@Mapper
public interface SeckillGoodsMapper extends BaseMapper<SeckillGoods> {

    /** 锁定秒杀商品库存行，作为消费与最终补偿共同遵守的并发协调点。 */
    @Select("SELECT * FROM seckill_goods WHERE id = #{id} FOR UPDATE")
    SeckillGoods selectByIdForUpdate(@Param("id") Long id);
}
