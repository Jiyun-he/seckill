package com.example.high_concurrency_seckill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "秒杀商品")
public class SeckillGoodsVO {
    @Schema(description = "秒杀商品ID", example = "1")
    private Long id;
    @Schema(description = "关联的普通商品ID", example = "1001")
    private Long goodsId;
    @Schema(description = "秒杀价格", example = "99.90")
    private BigDecimal seckillPrice;
    @Schema(description = "秒杀库存", example = "100")
    private Integer seckillStock;
    @Schema(description = "秒杀开始时间", example = "2026-04-21T10:00:00")
    private LocalDateTime startTime;
    @Schema(description = "秒杀结束时间", example = "2026-04-21T12:00:00")
    private LocalDateTime endTime;
}
