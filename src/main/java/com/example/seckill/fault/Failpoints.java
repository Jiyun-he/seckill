package com.example.seckill.fault;

/**
 * 故障注入点（Failpoint）标识常量。
 *
 * <p>每个常量对应业务链路中的一个精确失败窗口，仅在 {@code fault-test} profile 下
 * 通过 {@link FailpointService} 生效，正常运行（非 fault-test）时全部为 No-Op。</p>
 *
 * @author jiyunhe
 */
public final class Failpoints {

    /** Redis 预占（Lua 扣减 + 建立预占状态）之后、发送 MQ 之前 */
    public static final String PREOCCUPY_AFTER = "preoccupy_after";

    /** 消息 Publish（convertAndSend）之后、confirm 回调返回之前 */
    public static final String PUBLISH_AFTER = "publish_after";

    /** 消费者事务提交（afterCommit）之后、ACK 之前 */
    public static final String COMMIT_AFTER = "commit_after";

    /** 死信补偿（compensateRedis）之后、ACK 之前 */
    public static final String COMPENSATE_AFTER = "compensate_after";

    /** 消费者幂等检查（selectCount）之后、INSERT 订单之前，用于放大 check-then-act 窗口 */
    public static final String INSERT_BEFORE = "insert_before";

    private Failpoints() {
    }
}
