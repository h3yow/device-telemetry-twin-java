package com.github.h3yow.devicetelemetrytwinjava.alarm;

/**
 * (device, rule) 维度的当前告警状态。
 */
public record AlarmState(String deviceId,
                         String ruleId,
                         AlarmStatus status,
                         int currentRevision,
                         long lastFiredEventId,
                         long lastFiredAt,
                         long lastEvaluatedSeq,
                         long lastEvaluatedAt) {
}
