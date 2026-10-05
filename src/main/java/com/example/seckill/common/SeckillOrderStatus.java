package com.example.seckill.common;

/**
 * 秒杀订单状态机。
 *
 * <p>其中 {@link #PENDING} / {@link #CONFIRMED} / {@link #FAILED} /
 * {@link #CONSUMED} 是 Redis 实际存储的投递状态；{@link #PROCESSING} 与 {@link #NOT_FOUND}
 * 仅作为对外查询结果，不写入 Redis。</p>
 *
 * <p>状态流转：预占成功 → PENDING →（confirm ack）CONFIRMED →（消费落库）CONSUMED；
 * 仅当消息确定未发出（同步异常 / 无法路由被退回）才立即补偿为 FAILED；
 * 其余情况（confirm nack、回音丢失）状态停留在 PENDING，由对账框架按超时以 DB 事实收敛。</p>
 *
 * @author jiyunhe
 */
public enum SeckillOrderStatus {
    /** 存储：预占成功，消息发送中（confirm 前） */
    PENDING,
    /** 存储：confirm ack，消息已投递 Broker，等待消费落库 */
    CONFIRMED,
    /** 存储+对外：最终失败（补偿完成） */
    FAILED,
    /** 存储+对外：消费落库成功 */
    CONSUMED,
    /** 仅对外：中间态（PENDING/CONFIRMED）合并为处理中 */
    PROCESSING,
    /** 仅对外：查询兜底，Redis 无状态且数据库无订单 */
    NOT_FOUND;

    /** 中间态（PENDING/CONFIRMED）Redis 保留时长（秒），超时由对账框架兜底 */
    public static final long INTERMEDIATE_TTL_SECONDS = 3600L;
    /** 终态（FAILED/CONSUMED）Redis 保留时长（秒），保证用户有可查询窗口 */
    public static final long FINAL_TTL_SECONDS = 86400L;

    /**
     * 对外可见状态：把中间态（PENDING/CONFIRMED）合并为 PROCESSING，
     * 终态（FAILED/CONSUMED）与 NOT_FOUND 原样返回。
     */
    public SeckillOrderStatus toUserVisible() {
        switch (this) {
            case PENDING:
            case CONFIRMED:
                return PROCESSING;
            default:
                return this;
        }
    }
}
