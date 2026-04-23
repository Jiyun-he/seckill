package com.example.high_concurrency_seckill.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "统一响应包装")
public class Result<T> {
    @Schema(description = "业务状态码：200-成功，500-失败", example = "200", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer code;
    @Schema(description = "提示信息", example = "success", requiredMode = Schema.RequiredMode.REQUIRED)
    private String msg;
    @Schema(description = "响应数据")
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMsg("success");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(String msg) {
        Result<T> result = new Result<>();
        result.setCode(500);
        result.setMsg(msg);
        return result;
    }
}
