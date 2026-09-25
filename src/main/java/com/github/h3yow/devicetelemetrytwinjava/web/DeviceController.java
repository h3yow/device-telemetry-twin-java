package com.github.h3yow.devicetelemetrytwinjava.web;

import com.github.h3yow.devicetelemetrytwinjava.device.DeviceProfile;
import com.github.h3yow.devicetelemetrytwinjava.device.DeviceRegistry;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 设备注册 HTTP 入口。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceRegistry registry;

    public DeviceController(DeviceRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    public DeviceProfile register(@RequestBody RegisterRequest request) {
        return registry.register(request.deviceId(),
                request.allowedMetrics() == null ? Set.of() : request.allowedMetrics());
    }

    public record RegisterRequest(String deviceId, Set<String> allowedMetrics) {
    }
}
