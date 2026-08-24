package com.example.seckill.fault;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

/**
 * 故障注入服务的 No-Op 实现，用于非 fault-test profile。
 * 所有方法为空操作，保证正常运行零开销。
 *
 * @author jiyunhe
 */
@Service
@Profile("!fault-test")
public class NoopFailpointService implements FailpointService {

    @Override
    public void block(String id) {
        // No-Op
    }

    @Override
    public void throwIfEnabled(String id) {
        // No-Op
    }

    @Override
    public void enableBlock(String id) {
        // No-Op
    }

    @Override
    public void enableThrow(String id) {
        // No-Op
    }

    @Override
    public void release(String id) {
        // No-Op
    }

    @Override
    public void disable(String id) {
        // No-Op
    }

    @Override
    public Map<String, Object> status(String id) {
        return Collections.emptyMap();
    }

    @Override
    public Map<String, Object> statusAll() {
        return Collections.emptyMap();
    }
}
