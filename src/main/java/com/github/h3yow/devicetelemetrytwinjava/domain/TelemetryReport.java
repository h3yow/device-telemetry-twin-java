package com.github.h3yow.devicetelemetrytwinjava.domain;

import java.util.Map;

/**
 * 一次设备遥测上报。
 *
 * @param deviceId   设备唯一标识
 * @param seq        设备侧单调递增序列号，用于乱序/重复判定
 * @param ts         事件时间戳（epoch millis），由设备时钟产生
 * @param metrics    指标名到指标值的载荷
 * @param receivedAt 平台接收时间戳（epoch millis）
 */
public record TelemetryReport(String deviceId,
                              long seq,
                              long ts,
                              Map<String, Double> metrics,
                              long receivedAt) {

    public TelemetryReport {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    /** 以当前系统时间作为接收时间的便捷构造。 */
    public static TelemetryReport of(String deviceId, long seq, long ts, Map<String, Double> metrics) {
        return new TelemetryReport(deviceId, seq, ts, metrics, System.currentTimeMillis());
    }
}
