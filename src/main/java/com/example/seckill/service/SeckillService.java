package com.example.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.vo.SeckillGoodsVO;
import com.example.seckill.vo.SeckillOrderStatusVO;

/**
 * 秒杀服务接口。
 *
 * @author jiyunhe
 */

public interface SeckillService extends IService<SeckillGoods> {

    /**
     * 将数据库中的秒杀商品库存预热到 Redis。
     * 仅在 Redis key 不存在时写入，避免应用重启覆盖运行期间已变更的库存。
     */
    void loadSeckillStockToRedis();

    /**
     * 执行秒杀下单：从 Redis 读取活动时间段（不访问 MySQL），先生成订单号，
     * 再由单个 Lua 脚本原子完成「校验时间段 + 一人一单查重 + 扣减库存 + 占位 + 建立订单预占状态」，
     * 成功后发送消息到 MQ 异步落库。
     *
     * @param userId        秒杀用户 ID
     * @param seckillGoodsId 秒杀商品 ID
     * @return 生成的订单号（雪花算法）
     * @throws RuntimeException 秒杀活动不存在、不在秒杀时间段、重复下单、库存不足、
     *                          库存未初始化、库存数据异常、系统繁忙等场景抛出
     */
    Long seckill(Long userId, Long seckillGoodsId);

    /**
     * 查询秒杀商品详情，优先读缓存，缓存未命中时回源数据库并回填缓存；
     * 返回结果中的库存为 Redis 中的实时库存。
     *
     * @param id 秒杀商品 ID
     * @return 秒杀商品详情 VO；商品不存在时返回 null
     */
    SeckillGoodsVO getSeckillGoodsDetail(Long id);

    /**
     * 查询秒杀订单异步状态：优先读 Redis 预占状态，未命中时兜底查数据库订单。
     *
     * @param orderNo 订单号
     * @return 订单状态（PROCESSING / SUCCESS / FAILED / NOT_FOUND）
     */
    SeckillOrderStatusVO getSeckillOrderStatus(Long orderNo);
}
