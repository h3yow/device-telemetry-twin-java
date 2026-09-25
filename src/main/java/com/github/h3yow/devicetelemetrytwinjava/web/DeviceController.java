package com.github.h3yow.devicetelemetrytwinjava.web;

import com.github.h3yow.devicetelemetrytwinjava.domain.Device;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 设备注册入口（进程内注册中心）。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceRegistry deviceRegistry;

    public DeviceController(DeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    /**
     * 注册设备。请求体形如：
     * <pre>{"deviceId":"d1","name":"温度计",
     * "metricBounds":{"temperature":{"min":-40,"max":85}}}</pre>
     */
    @PutMapping
    public Device register(@RequestBody RegisterRequest req) {
        Map<String, Device.MetricBounds> bounds = req.metricBounds() == null
                ? Map.of() : req.metricBounds();
        return deviceRegistry.register(new Device(req.deviceId(), req.name(), bounds));
    }

    @GetMapping("/{deviceId}")
    public ResponseEntity<Device> get(@PathVariable String deviceId) {
        return deviceRegistry.find(deviceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<Device> all() {
        return List.copyOf(deviceRegistry.all());
    }

    /** 设备注册请求体。 */
    public record RegisterRequest(String deviceId,
                                  String name,
                                  Map<String, Device.MetricBounds> metricBounds) {
    }
}
