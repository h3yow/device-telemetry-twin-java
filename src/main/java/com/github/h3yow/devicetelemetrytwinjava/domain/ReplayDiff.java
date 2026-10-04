package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 试算结果与线上生效规则结果之间的一条差异。
 * 每条差异都携带两侧规则版本与触发/恢复时的输入值，便于复核“是基于哪版规则、由哪些输入算出来的”。
 *
 * @param type              差异类型
 * @param ruleId            关联的规则标识
 * @param metric            指标名
 * @param liveRuleVersion   线上生效规则版本（线上侧缺失时为 null）
 * @param liveAlert         线上告警快照（差异涉及线上侧时非 null）
 * @param candidateAlert    试算告警片段（差异涉及候选侧时非 null）
 * @param detail            人类可读的差异说明（含输入值与阈值依据）
 */
public record ReplayDiff(Type type,
                         String ruleId,
                         String metric,
                         Integer liveRuleVersion,
                         AlertView liveAlert,
                         ReplayAlert candidateAlert,
                         String detail) {

    /**
     * 差异类型：
     * ADDED —— 候选规则多报（线上不会触发）；
     * REMOVED —— 候选规则少报（线上会触发，候选不再触发）；
     * CHANGED —— 两侧都触发，但触发时刻或持续时长不同。
     */
    public enum Type {
        ADDED,
        REMOVED,
        CHANGED
    }
}
