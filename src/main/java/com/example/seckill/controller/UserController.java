package com.example.seckill.controller;

import com.example.seckill.common.Result;
import com.example.seckill.dto.LoginDTO;
import com.example.seckill.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 用户注册与登录接口。
 *
 * @author jiyunhe
 */

@RestController
@RequestMapping("/user")
@Tag(name = "用户")
public class UserController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    @Operation(summary = "用户注册", description = "注册成功后返回 JWT Token")
    public Result<String> register(@Validated @RequestBody LoginDTO loginDTO) {
        String token = userService.register(loginDTO.getUsername(), loginDTO.getPassword());
        return Result.success(token);
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "登录成功后返回 JWT Token")
    public Result<String> login(@Validated @RequestBody LoginDTO loginDTO) {
        String token = userService.login(loginDTO.getUsername(), loginDTO.getPassword());
        return Result.success(token);
    }
}
