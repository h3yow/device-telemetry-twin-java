package com.github.h3yow.devicetelemetrytwinjava.telemetry;

import java.util.Map;

/**
 * 设备遥测上报。
 *
 * @param deviceId  设备唯一标识
 * @param metrics   指标名 -> 指标值
 * @param timestamp 事件时间（epoch millis，设备侧产生时间）
 * @param seq       设备内单调递增序列号，乱序/重复判定的版本依据
 * @param messageId 报文唯一标识（幂等键），重发必须复用同一 messageId
 */
public record TelemetryMessage(String deviceId,
                               Map<String, Double> metrics,
                               long timestamp,
                               long seq,
                               String messageId) {
}
