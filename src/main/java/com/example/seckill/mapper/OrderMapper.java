package com.example.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.seckill.entity.Order;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 订单 Mapper。
 *
 * @author jiyunhe
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /** 当前读并锁定指定订单号；订单不存在时由 InnoDB 按当前隔离级别处理索引间隙。 */
    @Select("SELECT * FROM `order` WHERE order_no = #{orderNo} FOR UPDATE")
    Order selectByOrderNoForUpdate(@Param("orderNo") Long orderNo);
}
