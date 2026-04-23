package com.example.high_concurrency_seckill.config;

import com.example.high_concurrency_seckill.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 从请求头获取 token
        String token = request.getHeader("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            response.setStatus(401);
            response.getWriter().write("未登录");
            return false;
        }
        token = token.substring(7);
        // 验证 JWT 有效性
        if (!jwtUtil.validateToken(token)) {
            response.setStatus(401);
            response.getWriter().write("token无效");
            return false;
        }
        // 从 JWT 获取 userId
        Long userId = jwtUtil.getUserIdFromToken(token);
        // 验证 Redis 中 token 是否存在且匹配（可选）
        String redisToken = redisTemplate.opsForValue().get("token:" + userId);
        if (redisToken == null || !redisToken.equals(token)) {
            response.setStatus(401);
            response.getWriter().write("token已失效");
            return false;
        }
        // 将 userId 存入 request 属性，后续 controller 可获取
        request.setAttribute("userId", userId);
        return true;
    }
}