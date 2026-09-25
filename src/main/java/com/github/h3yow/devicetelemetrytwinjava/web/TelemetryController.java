package com.github.h3yow.devicetelemetrytwinjava.web;

import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryReport;
import com.github.h3yow.devicetelemetrytwinjava.service.TelemetryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 遥测接入入口（模拟平台 northbound 接口）。
 */
@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    /**
     * 上报遥测。请求体形如：
     * <pre>{"deviceId":"sim-thermo-001","seq":1,"ts":1727000000000,
     * "metrics":{"temperature":36.5}}</pre>
     * receivedAt 缺省取服务器时间。
     */
    @PostMapping("/reports")
    public TelemetryService.IngestOutcome report(@RequestBody TelemetryRequest req) {
        long receivedAt = req.receivedAt() != null ? req.receivedAt() : System.currentTimeMillis();
        TelemetryReport report = new TelemetryReport(req.deviceId(), req.seq(), req.ts(),
                req.metrics(), receivedAt);
        return telemetryService.ingestWithAlerts(report);
    }

    /** 遥测上报请求体。 */
    public record TelemetryRequest(String deviceId,
                                   long seq,
                                   long ts,
                                   java.util.Map<String, Double> metrics,
                                   Long receivedAt) {
    }
}
