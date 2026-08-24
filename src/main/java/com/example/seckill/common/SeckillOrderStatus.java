package com.example.seckill.common;

/**
 * 秒杀订单状态机。
 *
 * <p>其中 {@link #PENDING} / {@link #CONFIRMED} / {@link #RETRY} / {@link #FAILED} /
 * {@link #CONSUMED} 是 Redis 实际存储的投递状态；{@link #PROCESSING} 与 {@link #NOT_FOUND}
 * 仅作为对外查询结果，不写入 Redis。</p>
 *
 * <p>状态流转：预占成功 → PENDING →（confirm ack）CONFIRMED →（消费落库）CONSUMED；
 * confirm 超时 → RETRY（交对账框架）；明确失败（nack / return / 同步异常）→ FAILED。</p>
 *
 * @author jiyunhe
 */
public enum SeckillOrderStatus {
    /** 存储：预占成功，消息发送中（confirm 前） */
    PENDING,
    /** 存储：confirm ack，消息已投递 Broker，等待消费落库 */
    CONFIRMED,
    /** 存储：confirm 超时/结果未知，待对账框架重试裁决 */
    RETRY,
    /** 存储+对外：最终失败（补偿完成） */
    FAILED,
    /** 存储+对外：消费落库成功 */
    CONSUMED,
    /** 仅对外：中间态（PENDING/CONFIRMED/RETRY）合并为处理中 */
    PROCESSING,
    /** 仅对外：查询兜底，Redis 无状态且数据库无订单 */
    NOT_FOUND;

    /** 中间态（PENDING/CONFIRMED/RETRY）Redis 保留时长（秒），超时由对账框架兜底 */
    public static final long INTERMEDIATE_TTL_SECONDS = 3600L;
    /** 终态（FAILED/CONSUMED）Redis 保留时长（秒），保证用户有可查询窗口 */
    public static final long FINAL_TTL_SECONDS = 86400L;

    /**
     * 对外可见状态：把中间态（PENDING/CONFIRMED/RETRY）合并为 PROCESSING，
     * 终态（FAILED/CONSUMED）与 NOT_FOUND 原样返回。
     */
    public SeckillOrderStatus toUserVisible() {
        switch (this) {
            case PENDING:
            case CONFIRMED:
            case RETRY:
                return PROCESSING;
            default:
                return this;
        }
    }
}
