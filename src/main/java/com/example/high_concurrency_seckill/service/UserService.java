package com.example.high_concurrency_seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.high_concurrency_seckill.entity.User;

public interface UserService extends IService<User> {
    String register(String username, String password);
    String login(String username, String password);
}