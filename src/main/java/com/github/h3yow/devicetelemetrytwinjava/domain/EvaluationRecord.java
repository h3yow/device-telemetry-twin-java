package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 一次“遥测 × 规则”的判定历史记录（只追加）。
 * 规则变更后，可依据记录中的 {@code ruleVersion / threshold / operator} 与输入值复现历史判定。
 *
 * @param deviceId    设备 ID
 * @param ruleId      规则 ID
 * @param ruleVersion 判定时使用的规则版本
 * @param operator    判定时的算子
 * @param threshold   判定时的阈值
 * @param metric      指标名
 * @param value       本次输入值
 * @param violated    阈值条件是否成立
 * @param verdict     告警判定结论
 * @param alertId     关联告警 ID（无告警时为 null）
 * @param evaluatedAt 判定时间（epoch millis，取事件处理时刻）
 */
public record EvaluationRecord(String deviceId,
                               String ruleId,
                               int ruleVersion,
                               AlertRule.Operator operator,
                               double threshold,
                               String metric,
                               double value,
                               boolean violated,
                               AlertVerdict verdict,
                               String alertId,
                               long evaluatedAt) {
}
