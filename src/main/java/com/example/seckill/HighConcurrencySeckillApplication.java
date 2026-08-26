package com.example.seckill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 秒杀系统启动类。
 *
 * @author jiyunhe
 */

@SpringBootApplication
@EnableScheduling
public class HighConcurrencySeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(HighConcurrencySeckillApplication.class, args);
    }

}
