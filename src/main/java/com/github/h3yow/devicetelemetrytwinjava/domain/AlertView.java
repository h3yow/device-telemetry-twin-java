package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 告警的不可变快照（视图对象）。
 */
public record AlertView(String alertId,
                        String ruleId,
                        String deviceId,
                        String metric,
                        int ruleVersion,
                        AlertState state,
                        double triggerValue,
                        long triggeredAt,
                        Long suppressedUntil,
                        Long resolvedAt,
                        double resolveValue,
                        long lastEvaluatedAt,
                        int triggerCount) {
}
