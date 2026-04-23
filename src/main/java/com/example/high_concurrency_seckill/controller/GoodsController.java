package com.example.high_concurrency_seckill.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.high_concurrency_seckill.common.Result;
import com.example.high_concurrency_seckill.service.GoodsService;
import com.example.high_concurrency_seckill.vo.GoodsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/goods")
@Tag(name = "商品")
@SecurityRequirement(name = "bearerAuth")
public class GoodsController {

    @Autowired
    private GoodsService goodsService;

    @GetMapping("/list")
    @Operation(summary = "分页查询商品列表", description = "支持按关键字模糊搜索商品名称")
    public Result<Page<GoodsVO>> list(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页条数", example = "10")
            @RequestParam(defaultValue = "10") Integer size,
            @Parameter(description = "关键字（商品名称模糊匹配）", example = "iPhone")
            @RequestParam(required = false) String keyword
    ) {
        return Result.success(goodsService.listGoods(page, size, keyword));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询商品详情")
    public Result<GoodsVO> detail(@Parameter(description = "商品ID", required = true) @PathVariable Long id) {
        GoodsVO goods = goodsService.getGoodsDetail(id);
        if (goods == null) {
            return Result.error("商品不存在");
        }
        return Result.success(goods);
    }
}
