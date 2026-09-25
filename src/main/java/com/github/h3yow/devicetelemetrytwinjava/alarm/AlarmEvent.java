package com.github.h3yow.devicetelemetrytwinjava.alarm;

import java.util.Map;

/**
 * 不可变告警事件（生命周期留痕）。
 *
 * @param eventId    事件标识
 * @param deviceId   设备
 * @param ruleId     规则
 * @param revision   判定时使用的规则 revision（历史判定复现依据）
 * @param type       FIRE / SUPPRESSED / RECOVER
 * @param metrics    判定时的指标快照
 * @param seq        判定时遥测 seq
 * @param timestamp  判定时遥测事件时间
 * @param evaluatedAt 判定执行时间（墙钟）
 * @param basis      判定依据描述（日志/审计用）
 */
public record AlarmEvent(long eventId,
                         String deviceId,
                         String ruleId,
                         int revision,
                         AlarmEventType type,
                         Map<String, Double> metrics,
                         long seq,
                         long timestamp,
                         long evaluatedAt,
                         String basis) {
}
