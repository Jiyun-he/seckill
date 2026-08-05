package com.example.seckill.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带 HTTP 状态码，供全局异常处理器映射响应状态。
 *
 * @author jiyunhe
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public BusinessException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
