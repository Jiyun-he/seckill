package com.example.seckill.controller;

import com.example.seckill.common.Result;
import com.example.seckill.entity.User;
import com.example.seckill.mapper.UserMapper;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * 压测辅助接口。
 *
 * @author jiyunhe
 */

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
