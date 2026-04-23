package com.example.high_concurrency_seckill.controller;

import com.example.high_concurrency_seckill.common.Result;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
public class HelloController {

    @GetMapping("/hello")
    public Result<String> hello() {
        return Result.success("Hello Seckill System");
    }
}
