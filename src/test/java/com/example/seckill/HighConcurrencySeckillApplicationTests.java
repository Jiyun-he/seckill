package com.example.seckill;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冒烟测试：验证 Testcontainers 中间件 + Spring 上下文能正常启动，且秒杀库存预热到 Redis。
 *
 * @author jiyunhe
 */
@SpringBootTest(classes = SeckillTestApplication.class)
class HighConcurrencySeckillApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoadsAndPreheatsStock() {
        // 种子秒杀商品 id=1 的库存（startTime=2026-01-01 00:00:00 → 版本 20260101000000）已预热
        String stock = stringRedisTemplate.opsForValue().get("seckill:stock:1:20260101000000");
        assertThat(stock).isEqualTo("50");
    }
}
