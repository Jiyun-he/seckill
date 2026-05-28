package com.example.high_concurrency_seckill.controller;

import com.example.high_concurrency_seckill.common.Result;
import com.example.high_concurrency_seckill.dto.OrderDTO;
import com.example.high_concurrency_seckill.service.OrderService;
import com.example.high_concurrency_seckill.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/order")
@Tag(name = "订单")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping("/create")
    @Operation(
            summary = "创建订单",
            description = "需要登录；基于拦截器注入的 userId 创建订单"
    )
    public Result<OrderVO> create(@Validated @RequestBody OrderDTO orderDTO,
                                HttpServletRequest request) {
        // 从请求属性获取userId（拦截器已存入）
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return Result.error("未登录");
        }
        OrderVO order = orderService.createOrder(userId, orderDTO.getGoodsId(), orderDTO.getQuantity());
        return Result.success(order);
    }
}
