package com.example.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.seckill.entity.User;

/**
 * 用户服务接口。
 *
 * @author jiyunhe
 */

public interface UserService extends IService<User> {

    /**
     * 用户注册：校验用户名唯一性，密码经 BCrypt 加密后入库，注册成功后即生成 JWT
     * 并写入 Redis（key 为 "token:userId"，过期时间与 JWT 一致）。
     *
     * @param username 用户名
     * @param password 明文密码，将加密后存储
     * @return 注册成功生成的 JWT Token
     * @throws RuntimeException 用户名已存在时抛出
     */
    String register(String username, String password);

    /**
     * 用户登录：校验用户名与密码，验证通过后生成 JWT 并写入 Redis，
     * 实现登录态的无状态下发与统一管理。
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 登录成功生成的 JWT Token
     * @throws RuntimeException 用户名或密码错误时抛出
     */
    String login(String username, String password);
}