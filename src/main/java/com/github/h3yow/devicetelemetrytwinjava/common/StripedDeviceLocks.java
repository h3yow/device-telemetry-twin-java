package com.github.h3yow.devicetelemetrytwinjava.common;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按 deviceId 维度的分段锁：同一设备的接入、影子、告警判定在同一条临界区内串行，
 * 保证多入口并发下同一设备状态一致且可解释。ReentrantLock 允许同线程跨组件重入。
 */
@Component
public class StripedDeviceLocks {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock lockFor(String deviceId) {
        return locks.computeIfAbsent(deviceId, k -> new ReentrantLock());
    }
}
