package com.example.high_concurrency_seckill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Data
@Schema(description = "创建订单请求体")
public class OrderDTO {
    @NotNull(message = "商品ID不能为空")
    @Schema(description = "商品ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long goodsId;
    @NotNull(message = "数量不能为空")
    @Positive(message = "数量必须大于0")
    @Schema(description = "购买数量", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer quantity;
}
