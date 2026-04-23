package com.example.high_concurrency_seckill.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password; // 加密后存储
    private String phone;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}