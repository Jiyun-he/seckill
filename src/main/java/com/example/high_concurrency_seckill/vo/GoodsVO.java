package com.example.high_concurrency_seckill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "商品")
public class GoodsVO {
    @Schema(description = "商品ID", example = "1001")
    private Long id;
    @Schema(description = "商品名称", example = "示例商品")
    private String name;
    @Schema(description = "商品价格", example = "199.00")
    private BigDecimal price;
    @Schema(description = "库存", example = "100")
    private Integer stock;
    @Schema(description = "商品详情", example = "商品详情描述")
    private String detail;
    @Schema(description = "创建时间", example = "2026-04-21T10:00:00")
    private LocalDateTime createTime;
}
