package com.example.seckill.util;

/**
 * 雪花算法 ID 生成器（单机简化版）
 * <p>
 * 结构：1bit 符号位 + 41bit 时间戳 + 5bit 数据中心ID + 5bit 工作机器ID + 12bit 序列号
 * 单机场景下 workerId 和 datacenterId 均为 0，每秒可生成约 400w 个 ID。
 */
public class SnowflakeIdUtil {

    /** 起始时间戳：2026-01-01 00:00:00 (UTC+8) */
    private static final long START_TIMESTAMP = 1767225600000L;

    /** 机器 ID 占用的位数 */
    private static final long WORKER_ID_BITS = 5L;
    /** 数据中心 ID 占用的位数 */
    private static final long DATACENTER_ID_BITS = 5L;
    /** 序列号占用的位数 */
    private static final long SEQUENCE_BITS = 12L;

    /** 最大机器 ID（5bit = 31） */
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    /** 最大数据中心 ID（5bit = 31） */
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    /** 最大序列号（12bit = 4095） */
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    /** 机器 ID 左移 12 位 */
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    /** 数据中心 ID 左移 17 位 */
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    /** 时间戳左移 22 位 */
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    /** 工作机器 ID（0~31） */
    private final long workerId;
    /** 数据中心 ID（0~31） */
    private final long datacenterId;
    /** 序列号（0~4095） */
    private long sequence = 0L;
    /** 上次生成 ID 的时间戳 */
    private long lastTimestamp = -1L;

    /** 单例 */
    private static final SnowflakeIdUtil INSTANCE = new SnowflakeIdUtil(0, 0);

    private SnowflakeIdUtil(long workerId, long datacenterId) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException("workerId 必须在 0~" + MAX_WORKER_ID + " 之间");
        }
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException("datacenterId 必须在 0~" + MAX_DATACENTER_ID + " 之间");
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    /**
     * 生成下一个 ID（线程安全）
     */
    public static synchronized long nextId() {
        return INSTANCE.next();
    }

    private synchronized long next() {
        long timestamp = System.currentTimeMillis();

        // 时钟回拨：如果当前时间小于上次生成 ID 的时间，等待直到追上
        if (timestamp < lastTimestamp) {
            try {
                Thread.sleep(lastTimestamp - timestamp);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("雪花算法时钟中断", e);
            }
            timestamp = System.currentTimeMillis();
        }

        if (timestamp == lastTimestamp) {
            // 同一毫秒内，序列号递增
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // 序列号溢出，等待下一毫秒
                timestamp = nextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - START_TIMESTAMP) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long nextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
