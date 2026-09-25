package com.github.h3yow.devicetelemetrytwinjava.store;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按设备 ID 分配互斥锁。同一设备经由任何入口（遥测接入 / desired 写入 / 读取 /
 * 抑制与恢复）的状态变更都在同一把锁上串行化；不同设备互不阻塞。
 */
@Component
public class DeviceLocks {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock forDevice(String deviceId) {
        return locks.computeIfAbsent(deviceId, k -> new ReentrantLock());
    }
}
