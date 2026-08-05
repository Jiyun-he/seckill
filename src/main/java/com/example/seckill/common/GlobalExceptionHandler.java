package com.example.seckill.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器，将异常映射为语义化的 HTTP 状态码与统一响应体。
 *
 * @author clanguagetrainee
 */

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 参数校验失败，返回 400。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + f.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(HttpStatus.BAD_REQUEST.value(), msg));
    }

    /**
     * 业务异常，按异常携带的状态码返回。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        HttpStatus status = e.getStatus();
        return ResponseEntity.status(status)
                .body(Result.error(status.value(), e.getMessage()));
    }

    /**
     * 兜底系统异常，返回 500，不暴露堆栈。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "系统异常"));
    }
}
