package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 一次试算回放产生的告警片段（触发 → 恢复 的完整或未完成生命周期）。
 *
 * @param ruleId        候选规则标识
 * @param metric        指标名
 * @param triggerValue  触发时的输入值
 * @param triggeredAt   触发时刻（取触发历史点的接收时间，与线上语义一致）
 * @param resolvedAt    恢复时刻；窗口结束时仍未恢复则为 null
 * @param resolveValue  恢复时的输入值（未恢复时为 null）
 * @param triggerCount  片段内越限判定的累计次数
 */
public record ReplayAlert(String ruleId,
                          String metric,
                          double triggerValue,
                          long triggeredAt,
                          Long resolvedAt,
                          Double resolveValue,
                          int triggerCount) {

    /** 持续时长（毫秒）；未恢复时返回 null。 */
    public Long durationMillis() {
        return resolvedAt == null ? null : resolvedAt - triggeredAt;
    }
}
