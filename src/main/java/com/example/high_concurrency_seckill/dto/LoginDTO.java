package com.example.high_concurrency_seckill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
@Schema(description = "登录/注册请求体")
public class LoginDTO {
    @NotBlank(message = "用户名不能为空")
    @Schema(description = "用户名", example = "test_user", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;
    @NotBlank(message = "密码不能为空")
    @Schema(description = "密码", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
}
