package com.example.seckill;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 活动版本 / 库存初始化测试（#20）。
 *
 * <p>验证版本化库存与活动时间段的预热，以及旧版（无活动版本）僵尸库存的清理，
 * 防止历史残留阻止新活动正确加载。</p>
 *
 * @author jiyunhe
 */
@SpringBootTest(classes = SeckillTestApplication.class)
class SeckillStockInitTest extends AbstractIntegrationTest {

    private static final String VERSIONED_STOCK_KEY = "seckill:stock:1:20260101000000";
    private static final String OLD_STOCK_KEY = "seckill:stock:1";
    private static final String OLD_ORDERED_KEY = "seckill:ordered:1";
    private static final String ACTIVITY_KEY = "seckill:activity:1";

    @Test
    void loadStock_正常初始化版本库存与活动时间() {
        assertThat(stringRedisTemplate.opsForValue().get(VERSIONED_STOCK_KEY)).isEqualTo("50");
        assertThat(stringRedisTemplate.opsForValue().get(ACTIVITY_KEY)).isNotNull();
    }

    @Test
    void loadStock_清理旧版僵尸库存() {
        // 伪造旧版（无活动版本）的僵尸库存与一人一单残留
        stringRedisTemplate.opsForValue().set(OLD_STOCK_KEY, "999");
        stringRedisTemplate.opsForSet().add(OLD_ORDERED_KEY, "ghost-user");

        seckillService.loadSeckillStockToRedis();

        // 旧 key 被清理，版本化库存以 DB 为准
        assertThat(stringRedisTemplate.opsForValue().get(OLD_STOCK_KEY)).isNull();
        assertThat(stringRedisTemplate.hasKey(OLD_ORDERED_KEY)).isFalse();
        assertThat(stringRedisTemplate.opsForValue().get(VERSIONED_STOCK_KEY)).isEqualTo("50");
    }
}
