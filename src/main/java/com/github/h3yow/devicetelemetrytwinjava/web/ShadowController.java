package com.github.h3yow.devicetelemetrytwinjava.web;

import com.github.h3yow.devicetelemetrytwinjava.common.StripedDeviceLocks;
import com.github.h3yow.devicetelemetrytwinjava.shadow.DeviceShadowService;
import com.github.h3yow.devicetelemetrytwinjava.shadow.ShadowState;
import com.github.h3yow.devicetelemetrytwinjava.shadow.ShadowUpdateResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 设备影子 HTTP 入口：读取 / 期望状态下发（乐观并发）。
 * desired 写入与遥测接入共用同一设备锁，多入口并发读写一致可解释。
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/shadow")
public class ShadowController {

    private final DeviceShadowService shadowService;
    private final StripedDeviceLocks locks;

    public ShadowController(DeviceShadowService shadowService, StripedDeviceLocks locks) {
        this.shadowService = shadowService;
        this.locks = locks;
    }

    @GetMapping
    public ResponseEntity<ShadowState> get(@PathVariable String deviceId) {
        return shadowService.get(deviceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/desired")
    public ResponseEntity<ShadowUpdateResult> updateDesired(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "-1") long expectedVersion,
            @RequestBody Map<String, Double> desired) {
        var lock = locks.lockFor(deviceId);
        lock.lock();
        try {
            ShadowUpdateResult result = shadowService.updateDesired(deviceId, desired, expectedVersion);
            HttpStatus http = result.accepted() ? HttpStatus.OK
                    : result.reason().startsWith("VERSION_CONFLICT") ? HttpStatus.CONFLICT
                    : result.reason().startsWith("STALE") ? HttpStatus.CONFLICT
                    : HttpStatus.UNPROCESSABLE_ENTITY;
            return ResponseEntity.status(http).body(result);
        } finally {
            lock.unlock();
        }
    }
}
