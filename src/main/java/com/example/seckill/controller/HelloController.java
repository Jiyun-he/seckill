package com.example.seckill.controller;

import com.example.seckill.common.Result;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连通性测试接口。
 *
 * @author jiyunhe
 */

@Hidden
@RestController
public class HelloController {

    @GetMapping("/hello")
    public Result<String> hello() {
        return Result.success("Hello Seckill System");
    }
}
