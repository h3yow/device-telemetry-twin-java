package com.github.h3yow.devicetelemetrytwinjava.web;

import com.github.h3yow.devicetelemetrytwinjava.domain.DeviceShadow;
import com.github.h3yow.devicetelemetrytwinjava.domain.ShadowUpdateResult;
import com.github.h3yow.devicetelemetrytwinjava.service.ShadowService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 设备影子入口：读取快照、写入期望状态（乐观版本校验）。
 */
@RestController
@RequestMapping("/api/shadows")
public class ShadowController {

    private final ShadowService shadowService;

    public ShadowController(ShadowService shadowService) {
        this.shadowService = shadowService;
    }

    @GetMapping("/{deviceId}")
    public ResponseEntity<DeviceShadow> get(@PathVariable String deviceId) {
        DeviceShadow shadow = shadowService.getShadow(deviceId);
        return shadow == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(shadow);
    }

    /**
     * 写入期望状态。请求体形如：
     * <pre>{"expectedVersion":2,"desired":{"temperature":25.0}}</pre>
     */
    @PutMapping("/{deviceId}/desired")
    public ShadowUpdateResult writeDesired(@PathVariable String deviceId,
                                           @RequestBody DesiredRequest req) {
        long expectedVersion = req.expectedVersion() == null ? -1L : req.expectedVersion();
        return shadowService.writeDesired(deviceId, req.desired(), expectedVersion);
    }

    /** desired 写入请求体。 */
    public record DesiredRequest(Long expectedVersion, Map<String, Double> desired) {
    }
}
