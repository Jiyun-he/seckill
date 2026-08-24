package com.example.seckill.fault;

/**
 * 故障注入异常，由 Failpoint 的 THROW 动作抛出，用于模拟某个失败窗口内的一步操作失败。
 *
 * @author jiyunhe
 */
public class FaultInjectionException extends RuntimeException {

    /** 触发异常的 failpoint 标识 */
    private final String failpointId;

    public FaultInjectionException(String failpointId) {
        super("Fault injected at failpoint: " + failpointId);
        this.failpointId = failpointId;
    }

    public String getFailpointId() {
        return failpointId;
    }
}
