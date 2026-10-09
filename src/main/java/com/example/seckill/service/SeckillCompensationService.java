package com.example.seckill.service;

import com.example.seckill.common.SeckillOrderStatus;
import com.example.seckill.entity.Order;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.fault.FailpointService;
import com.example.seckill.fault.Failpoints;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.SeckillGoodsMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 秒杀订单最终补偿协调器。
 *
 * <p>消费者与本服务都先锁定同一条 {@code seckill_goods} 记录，再访问订单记录和 Redis 状态。
 * 由此保证“确认无订单并补偿”和“消费者落库提交”不会针对同一商品并发越过彼此。</p>
 *
 * @author jiyunhe
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillCompensationService {

    public enum Result {
        COMPENSATED,
        ALREADY_FAILED,
        ORDER_EXISTS,
        INCONSISTENT,
        INVALID_STATE,
        GOODS_NOT_FOUND
    }

    private final SeckillGoodsMapper seckillGoodsMapper;
    private final OrderMapper orderMapper;
    private final SeckillOrderStateStore orderStateStore;
    private final FailpointService failpointService;

    /**
     * 在商品库存行锁保护下重新核对订单事实，仅当订单不存在时执行幂等补偿。
     */
    @Transactional(rollbackFor = Exception.class)
    public Result compensateIfNoOrder(
            Long userId, Long seckillGoodsId, String startTime, Long orderNo, String trigger) {
        SeckillGoods lockedGoods = seckillGoodsMapper.selectByIdForUpdate(seckillGoodsId);
        if (lockedGoods == null) {
            log.error("补偿裁决异常：订单 {} 对应秒杀商品 {} 不存在，trigger={}", orderNo, seckillGoodsId, trigger);
            return Result.GOODS_NOT_FOUND;
        }

        failpointService.block(Failpoints.COMPENSATE_LOCKED);

        Order order = orderMapper.selectByOrderNoForUpdate(orderNo);
        String redisStatus = orderStateStore.getStatus(orderNo);
        if (order != null) {
            if (SeckillOrderStatus.FAILED.name().equals(redisStatus)) {
                log.error("一致性异常：订单 {} 已落库但 Redis 为 FAILED，禁止覆盖，trigger={}", orderNo, trigger);
                return Result.INCONSISTENT;
            }
            long transition = orderStateStore.markConsumed(orderNo);
            if (transition == SeckillOrderStateStore.TERMINAL_CONFLICT) {
                log.error("一致性异常：订单 {} 已落库但 Redis 终态冲突，trigger={}", orderNo, trigger);
                return Result.INCONSISTENT;
            }
            if (transition == SeckillOrderStateStore.INVALID_STATE) {
                log.warn("订单 {} 已落库，但 Redis 状态不存在或非法，保留 DB 事实，trigger={}", orderNo, trigger);
            }
            return Result.ORDER_EXISTS;
        }

        if (SeckillOrderStatus.CONSUMED.name().equals(redisStatus)) {
            log.error("一致性异常：订单 {} 未落库但 Redis 为 CONSUMED，禁止补偿，trigger={}", orderNo, trigger);
            return Result.INCONSISTENT;
        }

        long compensated = orderStateStore.compensate(userId, seckillGoodsId, startTime, orderNo);
        if (compensated == SeckillOrderStateStore.APPLIED) {
            log.info("订单 {} 最终补偿完成，trigger={}", orderNo, trigger);
            return Result.COMPENSATED;
        }
        if (compensated == SeckillOrderStateStore.IDEMPOTENT) {
            return Result.ALREADY_FAILED;
        }
        if (compensated == SeckillOrderStateStore.TERMINAL_CONFLICT) {
            log.error("一致性异常：订单 {} 补偿时遇到 CONSUMED，trigger={}", orderNo, trigger);
            return Result.INCONSISTENT;
        }
        log.warn("订单 {} 补偿时 Redis 状态不存在或非法，trigger={}", orderNo, trigger);
        return Result.INVALID_STATE;
    }
}
