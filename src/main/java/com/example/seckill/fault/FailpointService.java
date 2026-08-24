package com.example.seckill.fault;

import java.util.Map;

/**
 * 故障注入服务：测试专用的旁路能力。
 *
 * <p>业务代码在失败窗口处调用 {@link #block(String)} / {@link #throwIfEnabled(String)} 埋点，
 * 仅在 {@code fault-test} profile 下注入真实故障，其他环境为 No-Op。</p>
 *
 * @author jiyunhe
 */
public interface FailpointService {

    /**
     * BLOCK：当指定 failpoint 处于 BLOCK 模式时，阻塞当前线程直到被 {@link #release(String)} 释放。
     * 未启用或非 BLOCK 模式时为 No-Op。
     */
    void block(String id);

    /**
     * THROW：当指定 failpoint 处于 THROW 模式时，抛出 {@link FaultInjectionException}。
     * 未启用或非 THROW 模式时为 No-Op。
     */
    void throwIfEnabled(String id);

    /** 启用 BLOCK 模式，之后命中该 failpoint 的线程会阻塞。 */
    void enableBlock(String id);

    /** 启用 THROW 模式，之后命中该 failpoint 的线程会抛出异常。 */
    void enableThrow(String id);

    /** 释放 BLOCK 模式，唤醒所有阻塞线程，并将该 failpoint 置为未启用。 */
    void release(String id);

    /** 禁用该 failpoint（等价于 release）。 */
    void disable(String id);

    /** 查询单个 failpoint 状态：{@code {id, mode, blockedThreads}}。 */
    Map<String, Object> status(String id);

    /** 查询所有 failpoint 状态。 */
    Map<String, Object> statusAll();
}
