package com.example.seckill.fault;

import com.example.seckill.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 故障注入控制端点，仅在 fault-test profile 下暴露。
 * 测试脚本通过该端点启用/释放 failpoint，控制 BLOCK/THROW。
 *
 * @author jiyunhe
 */
@RestController
@RequestMapping("/fault")
@Profile("fault-test")
@Tag(name = "故障注入")
public class FaultInjectionController {

    @Autowired
    private FailpointService failpointService;

    @PostMapping("/{id}/block")
    @Operation(summary = "启用 BLOCK 模式，命中后阻塞直到 release")
    public Result<Void> enableBlock(@Parameter(description = "failpoint 标识") @PathVariable String id) {
        failpointService.enableBlock(id);
        return Result.success(null);
    }

    @PostMapping("/{id}/throw")
    @Operation(summary = "启用 THROW 模式，命中后抛 FaultInjectionException")
    public Result<Void> enableThrow(@Parameter(description = "failpoint 标识") @PathVariable String id) {
        failpointService.enableThrow(id);
        return Result.success(null);
    }

    @PostMapping("/{id}/release")
    @Operation(summary = "释放 failpoint，唤醒所有阻塞线程")
    public Result<Void> release(@Parameter(description = "failpoint 标识") @PathVariable String id) {
        failpointService.release(id);
        return Result.success(null);
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "禁用 failpoint")
    public Result<Void> disable(@Parameter(description = "failpoint 标识") @PathVariable String id) {
        failpointService.disable(id);
        return Result.success(null);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询单个 failpoint 状态")
    public Result<Map<String, Object>> status(@Parameter(description = "failpoint 标识") @PathVariable String id) {
        return Result.success(failpointService.status(id));
    }

    @GetMapping
    @Operation(summary = "查询所有 failpoint 状态")
    public Result<Map<String, Object>> statusAll() {
        return Result.success(failpointService.statusAll());
    }
}
