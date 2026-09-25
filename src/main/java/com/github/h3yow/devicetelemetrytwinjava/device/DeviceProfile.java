package com.github.h3yow.devicetelemetrytwinjava.device;

import java.util.Set;

/**
 * 设备档案：declares 允许上报的指标集合，作为遥测与影子合法性校验依据。
 */
public record DeviceProfile(String deviceId, Set<String> allowedMetrics, long registeredAt) {
}
