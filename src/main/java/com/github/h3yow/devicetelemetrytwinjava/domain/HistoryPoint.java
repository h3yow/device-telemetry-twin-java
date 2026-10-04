package com.github.h3yow.devicetelemetrytwinjava.domain;

import java.util.Map;

/**
 * 一条被平台接受（ACCEPTED / DEGRADED）并写入影子的遥测历史点，用于规则试算回放。
 * 只记录通过清洗后的指标，保证回放输入与线上判定输入完全一致。
 *
 * @param deviceId   设备 ID
 * @param seq        生效序列号（平台分配或设备上报）
 * @param ts         事件时间戳（epoch millis）
 * @param metrics    清洗后的指标载荷（不可变）
 * @param receivedAt 平台接收时间（epoch millis），线上判定即以此作为判定时刻
 */
public record HistoryPoint(String deviceId,
                           long seq,
                           long ts,
                           Map<String, Double> metrics,
                           long receivedAt) {

    public HistoryPoint {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
}
