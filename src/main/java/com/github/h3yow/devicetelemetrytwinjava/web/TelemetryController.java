package com.github.h3yow.devicetelemetrytwinjava.web;

import com.github.h3yow.devicetelemetrytwinjava.common.IngestionStatus;
import com.github.h3yow.devicetelemetrytwinjava.pipeline.IngestionOutcome;
import com.github.h3yow.devicetelemetrytwinjava.pipeline.IngestionPipeline;
import com.github.h3yow.devicetelemetrytwinjava.telemetry.TelemetryMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 遥测接入 HTTP 入口。所有结论（含拒绝/降级）都通过 200 响应体中的 status 显式区分；
 * 仅报文无法解析时返回 400。
 */
@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final IngestionPipeline pipeline;

    public TelemetryController(IngestionPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @PostMapping
    public ResponseEntity<IngestionOutcome> ingest(@RequestBody TelemetryMessage message) {
        IngestionOutcome outcome = pipeline.ingest(message);
        IngestionStatus status = outcome.ingestion().status();
        // 真正应用到影子的返回 200；重复/过期/冲突/非法/未知设备返回 409 或 422 便于调用方区分
        HttpStatus http = switch (status) {
            case REJECTED_CONFLICT -> HttpStatus.CONFLICT;
            case REJECTED_STALE -> HttpStatus.CONFLICT;
            case REJECTED_DUPLICATE -> HttpStatus.CONFLICT;
            case REJECTED_INVALID, REJECTED_UNKNOWN_DEVICE -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(http).body(outcome);
    }
}
