package com.example.high_concurrency_seckill.controller;

import com.example.high_concurrency_seckill.common.Result;
import com.example.high_concurrency_seckill.service.SeckillService;
import com.example.high_concurrency_seckill.vo.SeckillGoodsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/seckill")
@Tag(name = "秒杀")
@SecurityRequirement(name = "bearerAuth")
public class SeckillController {

    @Autowired
    private SeckillService seckillService;

    @GetMapping("/goods/{id}")
    @Operation(summary = "查询秒杀商品详情", description = "带 Redis 缓存，包含缓存穿透与随机过期策略")
    public Result<SeckillGoodsVO> getSeckillGoods(@Parameter(description = "秒杀商品ID", required = true) @PathVariable Long id) {
        SeckillGoodsVO goods = seckillService.getSeckillGoodsDetail(id);
        if (goods == null) {
            return Result.error("秒杀商品不存在");
        }
        return Result.success(goods);
    }

    @PostMapping("/do/{seckillGoodsId}")
    @Operation(
            summary = "执行秒杀下单",
            description = "需要登录；下单成功返回订单号"
    )
    public Result<String> doSeckill(@Parameter(description = "秒杀商品ID", required = true) @PathVariable Long seckillGoodsId,
                                    HttpServletRequest request) {
        // 假设登录拦截器已经将userId存入request属性
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return Result.error("未登录");
        }
        String orderNo = seckillService.seckill(userId, seckillGoodsId);
        return Result.success(orderNo);
    }
}
