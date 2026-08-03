package com.example.seckill.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * JWT 生成、解析与校验工具类。
 *
 * @author jiyunhe
 */

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Getter
    @Value("${jwt.expiration}")
    private Long expiration;

    private SecretKey getSigningKey() {
        // secret 必须满足算法最低长度要求（例如 HS256 需要 >=32 字节）
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    /**
     * 生成 JWT Token
     */
    public String generateToken(Long userId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                // 设置主体（用户Id 或用户名）
                .subject(userId.toString())
                // 设置签发时间
                .issuedAt(now)
                // 设置过期时间
                .expiration(expiryDate)
                // 配置签名用的 key 和算法
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                // 生成最终 token 字符串
                .compact();
    }

    /**
     * 从 Token 中解析 userId
     */
    public Long getUserIdFromToken(String token) {
        JwtParser parser = Jwts.parser()
                // 指定验证签名使用的 key
                .verifyWith(getSigningKey())
                .build();

        Jws<Claims> jws = parser.parseSignedClaims(token);

        return Long.parseLong(jws.getPayload().getSubject());
    }

    /**
     * 验证 Token 是否有效
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}