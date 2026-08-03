package com.example.seckill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单视图对象。
 *
 * @author jiyunhe
 */

@Data
@Schema(description = "订单")
public class OrderVO {
    @Schema(description = "订单号（雪花算法生成）", example = "1767225600000001")
    private Long orderNo;
    @Schema(description = "用户ID", example = "2001")
    private Long userId;
    @Schema(description = "商品ID", example = "1001")
    private Long goodsId;
    @Schema(description = "商品名称（下单快照）", example = "示例商品")
    private String goodsName;
    @Schema(description = "商品单价（下单快照）", example = "199.00")
    private BigDecimal goodsPrice;
    @Schema(description = "购买数量", example = "1")
    private Integer quantity;
    @Schema(description = "订单总金额", example = "199.00")
    private BigDecimal totalAmount;
    @Schema(description = "订单状态：0-待支付，1-已支付，2-已取消", example = "0")
    private Integer status;
    @Schema(description = "创建时间", example = "2026-04-21T10:00:00")
    private LocalDateTime createTime;
}
