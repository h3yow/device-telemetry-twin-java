package com.github.h3yow.devicetelemetrytwinjava.device;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备注册表（进程内）。
 */
@Component
public class DeviceRegistry {

    private final ConcurrentHashMap<String, DeviceProfile> devices = new ConcurrentHashMap<>();

    public DeviceProfile register(String deviceId, Set<String> allowedMetrics) {
        DeviceProfile profile = new DeviceProfile(
                deviceId, Set.copyOf(allowedMetrics), System.currentTimeMillis());
        devices.put(deviceId, profile);
        return profile;
    }

    public Optional<DeviceProfile> find(String deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }
}
