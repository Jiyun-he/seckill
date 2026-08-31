package com.example.seckill;

import com.example.seckill.util.SnowflakeIdUtil;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 雪花算法基础唯一性测试（#19）。
 *
 * @author jiyunhe
 */
class SnowflakeIdUtilTest {

    @Test
    void 批量生成无重复() {
        SnowflakeIdUtil util = new SnowflakeIdUtil(0, 0);
        Set<Long> ids = new HashSet<>();
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            ids.add(util.nextId());
        }
        assertThat(ids).hasSize(n);
    }

    @Test
    void 不同workerId不碰撞() {
        SnowflakeIdUtil a = new SnowflakeIdUtil(0, 0);
        SnowflakeIdUtil b = new SnowflakeIdUtil(1, 0);
        Set<Long> ids = new HashSet<>();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            ids.add(a.nextId());
            ids.add(b.nextId());
        }
        assertThat(ids).hasSize(n * 2);
    }
}
