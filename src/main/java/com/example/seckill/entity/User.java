package com.example.seckill.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体。
 *
 * @author jiyunhe
 */
@Data
@TableName("user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** 加密后存储的密码 */
    @JsonIgnore private String password;

    private String phone;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
