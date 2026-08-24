package com.example.seckill.vo;

import com.example.seckill.common.SeckillOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 秒杀订单状态查询结果。
 *
 * @author jiyunhe
 */
@Data
@Schema(description = "秒杀订单状态")
public class SeckillOrderStatusVO {
    @Schema(description = "订单号", example = "1767225600000001")
    private Long orderNo;
    @Schema(description = "订单状态：PROCESSING-处理中，CONSUMED-成功，FAILED-失败，NOT_FOUND-不存在", example = "CONSUMED")
    private SeckillOrderStatus status;
}
