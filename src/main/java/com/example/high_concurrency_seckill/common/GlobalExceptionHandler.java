package com.example.high_concurrency_seckill.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;

@Slf4j
@Component
public class GlobalExceptionHandler implements HandlerExceptionResolver {

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request,
                                          HttpServletResponse response,
                                          Object handler, Exception ex) {
        Result<Void> result;
        if (ex instanceof MethodArgumentNotValidException e) {
            String msg = e.getBindingResult().getFieldErrors().stream()
                    .findFirst()
                    .map(f -> f.getField() + f.getDefaultMessage())
                    .orElse("参数校验失败");
            result = Result.error(msg);
        } else if (ex instanceof RuntimeException e) {
            log.error("运行时异常", e);
            result = Result.error(e.getMessage());
        } else {
            log.error("系统异常", ex);
            result = Result.error("系统异常");
        }

        try {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(objectMapper.writeValueAsString(result));
        } catch (IOException e) {
            log.error("响应写入失败", e);
        }

        return new ModelAndView();
    }
}
