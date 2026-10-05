package com.example.seckill.controller;

import com.example.seckill.common.BusinessException;
import com.example.seckill.common.Result;
import com.example.seckill.dto.OrderDTO;
import com.example.seckill.service.OrderService;
import com.example.seckill.vo.OrderVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口。
 *
 * @author jiyunhe
 */
@RestController
@RequestMapping("/order")
@Tag(name = "订单")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/create")
    @Operation(summary = "创建订单", description = "需要登录；基于拦截器注入的 userId 创建订单")
    public Result<OrderVO> create(
            @Validated @RequestBody OrderDTO orderDTO, HttpServletRequest request) {
        // 从请求属性获取userId（拦截器已存入）
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        OrderVO order =
                orderService.createOrder(userId, orderDTO.getGoodsId(), orderDTO.getQuantity());
        return Result.success(order);
    }
}
