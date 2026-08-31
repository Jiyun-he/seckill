package com.example.seckill;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 测试专用启动配置：与生产启动类唯一区别是不开启 {@code @EnableScheduling}，
 * 避免 {@code @Scheduled} 对账任务在测试期间并发干扰手工构造的 Redis 状态。
 *
 * @author jiyunhe
 */
@SpringBootApplication
public class SeckillTestApplication {
}
