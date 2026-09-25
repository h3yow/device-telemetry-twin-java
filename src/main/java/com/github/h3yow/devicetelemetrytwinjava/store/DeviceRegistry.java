package com.github.h3yow.devicetelemetrytwinjava.store;

import com.github.h3yow.devicetelemetrytwinjava.domain.Device;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内设备注册中心。线程安全，设备注册后不可变。
 */
@Component
public class DeviceRegistry {

    private final Map<String, Device> devices = new ConcurrentHashMap<>();

    public Device register(Device device) {
        devices.put(device.deviceId(), device);
        return device;
    }

    public Optional<Device> find(String deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }

    public boolean exists(String deviceId) {
        return devices.containsKey(deviceId);
    }

    public Collection<Device> all() {
        return devices.values();
    }
}
