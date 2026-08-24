package com.example.seckill.fault;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 故障注入服务的默认实现，仅在 fault-test profile 下生效。
 *
 * <p>每个 failpoint 有三种模式：NONE（未启用，No-Op）、BLOCK（命中即阻塞，直到 release）、
 * THROW（命中即抛 {@link FaultInjectionException}）。状态存于内存，由 {@link FaultInjectionController}
 * 通过 REST 端点控制。</p>
 *
 * @author jiyunhe
 */
@Slf4j
@Service
@Profile("fault-test")
public class DefaultFailpointService implements FailpointService {

    /** failpoint 模式 */
    private enum Mode { NONE, BLOCK, THROW }

    /** 单个 failpoint 的运行状态 */
    private static final class State {
        final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NONE);
        final AtomicInteger blockedThreads = new AtomicInteger(0);
        volatile CountDownLatch gate = new CountDownLatch(1);
    }

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    @Override
    public void block(String id) {
        State state = states.get(id);
        if (state == null || state.mode.get() != Mode.BLOCK) {
            return;
        }
        state.blockedThreads.incrementAndGet();
        log.info("[Failpoint] BLOCK hit: {}", id);
        try {
            state.gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            state.blockedThreads.decrementAndGet();
        }
    }

    @Override
    public void throwIfEnabled(String id) {
        State state = states.get(id);
        if (state != null && state.mode.get() == Mode.THROW) {
            log.info("[Failpoint] THROW hit: {}", id);
            throw new FaultInjectionException(id);
        }
    }

    @Override
    public void enableBlock(String id) {
        State state = states.computeIfAbsent(id, k -> new State());
        state.gate = new CountDownLatch(1);
        state.blockedThreads.set(0);
        state.mode.set(Mode.BLOCK);
        log.info("[Failpoint] enabled BLOCK: {}", id);
    }

    @Override
    public void enableThrow(String id) {
        State state = states.computeIfAbsent(id, k -> new State());
        state.mode.set(Mode.THROW);
        log.info("[Failpoint] enabled THROW: {}", id);
    }

    @Override
    public void release(String id) {
        State state = states.get(id);
        if (state == null) {
            return;
        }
        state.mode.set(Mode.NONE);
        state.gate.countDown();
        log.info("[Failpoint] released: {}", id);
    }

    @Override
    public void disable(String id) {
        release(id);
    }

    @Override
    public Map<String, Object> status(String id) {
        State state = states.get(id);
        if (state == null) {
            return Map.of("id", id, "mode", Mode.NONE.name(), "blockedThreads", 0);
        }
        return Map.of(
                "id", id,
                "mode", state.mode.get().name(),
                "blockedThreads", state.blockedThreads.get());
    }

    @Override
    public Map<String, Object> statusAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String id : states.keySet()) {
            result.put(id, status(id));
        }
        return result;
    }
}
