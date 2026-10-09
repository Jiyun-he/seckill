package com.example.seckill.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.seckill.common.SeckillOrderStatus;
import com.example.seckill.config.RabbitMQConfiguration;
import com.example.seckill.entity.Goods;
import com.example.seckill.entity.Order;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.fault.FailpointService;
import com.example.seckill.fault.Failpoints;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.SeckillGoodsMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * 秒杀订单 MQ 消费者，落库与死信补偿。
 *
 * @author jiyunhe
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderConsumer {

    private final OrderMapper orderMapper;
    private final SeckillGoodsMapper seckillGoodsMapper;
    private final SeckillGoodsService seckillGoodsService;
    private final GoodsService goodsService;
    private final SeckillOrderStateStore orderStateStore;
    private final SeckillCompensationService compensationService;
    private final FailpointService failpointService;

    /**
     * 消费秒杀订单队列消息，异步落库创建订单并扣减数据库库存。
     * 先按订单号做幂等检查，再校验秒杀商品与关联商品是否存在，插入订单后
     * 通过乐观锁扣减库存。
     *
     * @param msg 消息体，包含 userId、seckillGoodsId、orderNo 三个键
     * @throws RuntimeException 秒杀商品或关联商品不存在、库存不足时抛出，触发 MQ 重试
     */
    @RabbitListener(queues = RabbitMQConfiguration.SECKILL_QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void handleSeckillOrder(Map<String, Object> msg) {
        Long userId = Long.valueOf(msg.get("userId").toString());
        Long seckillGoodsId = Long.valueOf(msg.get("seckillGoodsId").toString());
        Long orderNo = ((Number) msg.get("orderNo")).longValue();
        String startTime = (String) msg.get("startTime");

        // 非锁定资料读取放在临界区之前，缩短热门商品库存行的持锁时间。
        SeckillGoods seckillGoods = seckillGoodsService.getById(seckillGoodsId);
        if (seckillGoods == null) {
            throw new RuntimeException("秒杀商品不存在");
        }
        Goods goods = goodsService.getById(seckillGoods.getGoodsId());
        if (goods == null) {
            throw new RuntimeException("关联商品不存在");
        }

        // 消费者与最终补偿统一锁顺序：seckill_goods -> order -> Redis 状态。
        // 获得商品行锁后必须重新核对 DB 与 Redis，之前的任何快照都不能作为提交依据。
        SeckillGoods lockedGoods = seckillGoodsMapper.selectByIdForUpdate(seckillGoodsId);
        if (lockedGoods == null) {
            throw new RuntimeException("秒杀商品不存在");
        }
        failpointService.block(Failpoints.CONSUME_LOCKED);

        Order existingOrder = orderMapper.selectByOrderNoForUpdate(orderNo);
        if (existingOrder != null) {
            long transition = orderStateStore.markConsumed(orderNo);
            if (transition == SeckillOrderStateStore.TERMINAL_CONFLICT) {
                log.error("一致性异常：订单 {} 已落库但 Redis 为 FAILED，禁止覆盖", orderNo);
            }
            return;
        }

        String statusName = orderStateStore.getStatus(orderNo);
        if (SeckillOrderStatus.FAILED.name().equals(statusName)) {
            log.info("订单 {} 已终止补偿，消费者不再落库", orderNo);
            return;
        }
        if (SeckillOrderStatus.CONSUMED.name().equals(statusName)) {
            log.error("一致性异常：订单 {} 未落库但 Redis 为 CONSUMED", orderNo);
            throw new AmqpRejectAndDontRequeueException("DB 无订单但 Redis 为 CONSUMED");
        }
        if (!SeckillOrderStatus.PENDING.name().equals(statusName)
                && !SeckillOrderStatus.CONFIRMED.name().equals(statusName)) {
            log.error("一致性异常：订单 {} 未落库且 Redis 状态不存在或非法：{}", orderNo, statusName);
            throw new AmqpRejectAndDontRequeueException("订单 Redis 状态不存在或非法");
        }

        failpointService.block(Failpoints.INSERT_BEFORE);

        if (lockedGoods.getSeckillStock() == null || lockedGoods.getSeckillStock() < 1) {
            calibrateRedisStock(
                    seckillGoodsId, startTime, userId, orderNo, lockedGoods.getSeckillStock());
            throw new AmqpRejectAndDontRequeueException("库存不足，已校准 Redis");
        }

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setGoodsId(seckillGoods.getGoodsId());
        order.setSeckillGoodsId(seckillGoodsId);
        order.setGoodsName(goods.getName());
        order.setGoodsPrice(seckillGoods.getSeckillPrice());
        order.setQuantity(1);
        order.setTotalAmount(seckillGoods.getSeckillPrice());
        order.setStatus(0);
        orderMapper.insert(order);

        boolean updated =
                seckillGoodsService.update(
                        new LambdaUpdateWrapper<SeckillGoods>()
                                .eq(SeckillGoods::getId, seckillGoodsId)
                                .ge(SeckillGoods::getSeckillStock, 1)
                                .setSql("seckill_stock = seckill_stock - 1"));
        if (!updated) {
            // 业务失败：DB 库存不足（Redis/DB 漂移），就地校准而非 incr，避免制造假库存
            calibrateRedisStock(
                    seckillGoodsId, startTime, userId, orderNo, lockedGoods.getSeckillStock());
            throw new AmqpRejectAndDontRequeueException("库存不足，已校准 Redis");
        }

        // SQL 提交后再确立 Redis 成功终态；严格 CAS 禁止覆盖 FAILED。
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        failpointService.block(Failpoints.COMMIT_BEFORE_STATUS);
                        failpointService.throwIfEnabled(Failpoints.COMMIT_BEFORE_STATUS);
                        long transition = orderStateStore.markConsumed(orderNo);
                        if (transition == SeckillOrderStateStore.TERMINAL_CONFLICT) {
                            log.error("一致性异常：订单 {} 已提交但 Redis 为 FAILED，禁止覆盖", orderNo);
                        } else if (transition == SeckillOrderStateStore.INVALID_STATE) {
                            log.warn("订单 {} 已提交但 Redis 状态不存在或非法", orderNo);
                        }
                        failpointService.block(Failpoints.COMMIT_AFTER);
                    }
                });
    }

    /**
     * 业务失败校准：DB 库存不足说明 Redis 与 DB 已漂移，将 Redis 库存同步为 DB 真实值，
     * 释放占位并标记失败，避免继续 incr 制造假库存。
     */
    private void calibrateRedisStock(
            Long seckillGoodsId,
            String startTime,
            Long userId,
            Long orderNo,
            Integer databaseStock) {
        int stock = databaseStock != null ? databaseStock : 0;
        long result = orderStateStore.calibrate(userId, seckillGoodsId, startTime, orderNo, stock);
        if (result == SeckillOrderStateStore.TERMINAL_CONFLICT) {
            log.error("一致性异常：订单 {} 校准库存时 Redis 已是 CONSUMED", orderNo);
        }
    }

    /**
     * 消费死信队列消息，对扣减成功的 Redis 库存与已下单记录执行补偿：
     * 通过 Lua 脚本原子地恢复 Redis 库存并将用户移出已下单集合，保证数据最终一致。
     *
     * @param msg 消息体，包含 userId、seckillGoodsId、orderNo、startTime（活动版本）四个键
     */
    @RabbitListener(queues = RabbitMQConfiguration.SECKILL_DLQ)
    public void handleDeadLetter(Map<String, Object> msg) {
        Long userId = Long.valueOf(msg.get("userId").toString());
        Long seckillGoodsId = Long.valueOf(msg.get("seckillGoodsId").toString());
        Long orderNo = ((Number) msg.get("orderNo")).longValue();

        String startTime = (String) msg.get("startTime");

        log.warn("订单 {} 进入死信队列，执行加锁补偿裁决", orderNo);
        compensationService.compensateIfNoOrder(userId, seckillGoodsId, startTime, orderNo, "DLQ");
        failpointService.block(Failpoints.COMPENSATE_AFTER);
    }
}
