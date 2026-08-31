package com.example.seckill;

import com.example.seckill.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 异常语义测试（#18）。
 *
 * <p>验证业务异常被全局异常处理器映射为语义化 HTTP 状态码，而非全部返回 200：
 * 商品不存在 404、重复秒杀 409、系统异常 500。通过真实 JWT 登录态走完整拦截器链路。</p>
 *
 * @author jiyunhe
 */
@SpringBootTest(classes = SeckillTestApplication.class)
@AutoConfigureMockMvc
class HttpSemanticsTest extends AbstractIntegrationTest {

    private static final long USER_ID = 2001L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;

    private String token;

    @BeforeEach
    void setupLogin() {
        // 生成合法 JWT 并写入 Redis 登录态，使拦截器放行（基类 resetState 已 flush 后再设置）
        token = jwtUtil.generateToken(USER_ID);
        stringRedisTemplate.opsForValue().set("token:" + USER_ID, token);
    }

    @Test
    void goods_不存在返回404() throws Exception {
        mockMvc.perform(get("/seckill/goods/999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void 重复秒杀返回409() throws Exception {
        mockMvc.perform(post("/seckill/do/1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/seckill/do/1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void 系统异常返回500() throws Exception {
        // 将库存值改成非数字，触发 Lua 返回 -3 → 500
        stringRedisTemplate.opsForValue().set("seckill:stock:1:20260101000000", "abc");

        mockMvc.perform(post("/seckill/do/1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isInternalServerError());
    }
}
