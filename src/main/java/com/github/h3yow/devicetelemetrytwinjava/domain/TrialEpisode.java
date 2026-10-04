package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 试算（或当前规则回放）得到的一段完整告警生命周期：从首次越限触发到回到阈值内恢复。
 * 全部字段由确定性回放计算，同一份历史 + 同一份规则重复试算结果完全一致。
 *
 * @param ruleId         规则 ID
 * @param ruleVersion    判定时使用的规则版本（候选规则为服务端分配的试算版本）
 * @param operator       判定时算子
 * @param threshold      判定时阈值
 * @param metric         指标名
 * @param seq            触发点序号（1 起，按时间顺序，便于差异复核定位输入）
 * @param triggeredAt    触发时刻（取触发上报的接收时间，与线上 evaluatedAt 口径一致）
 * @param triggerValue   触发输入值
 * @param peakValue      本段持续期间的最极端越限值
 * @param sampleCount    本段覆盖的越限上报数
 * @param resolvedAt     恢复时刻；null 表示到窗口结束仍未恢复（open 段）
 * @param resolveValue   恢复时的输入值；未恢复为 NaN
 * @param startTs        触发点事件时间戳
 * @param endTs          恢复点事件时间戳；未恢复为 null
 */
public record TrialEpisode(String ruleId,
                           int ruleVersion,
                           AlertRule.Operator operator,
                           double threshold,
                           String metric,
                           int seq,
                           long triggeredAt,
                           double triggerValue,
                           double peakValue,
                           int sampleCount,
                           Long resolvedAt,
                           double resolveValue,
                           long startTs,
                           Long endTs) {

    /** 段持续时长（毫秒）；未恢复段为 null。 */
    public Long durationMillis() {
        return resolvedAt == null ? null : resolvedAt - triggeredAt;
    }
}
