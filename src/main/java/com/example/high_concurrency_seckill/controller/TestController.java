package com.example.high_concurrency_seckill.controller;

import com.example.high_concurrency_seckill.common.Result;
import com.example.high_concurrency_seckill.entity.User;
import com.example.high_concurrency_seckill.mapper.UserMapper;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@Hidden
@RestController
public class TestController {

    @Autowired
    private UserMapper userMapper;

    @GetMapping("/test/db")
    public Result<List<User>> testDb() {
        List<User> users = userMapper.selectList(null);
        return Result.success(users);
    }
}
