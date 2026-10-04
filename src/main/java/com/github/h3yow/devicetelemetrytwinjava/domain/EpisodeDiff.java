package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 候选规则相对当前生效规则的单条差异。每条差异都可复核：携带两侧规则版本、
 * 两侧告警段（含触发/恢复时刻与输入值）。
 *
 * @param type             差异类型：NEW（新多出来的告警段）/ REMOVED（原来会报现在不报）/
 *                         CHANGED（都报，但触发时刻或持续时长/恢复时刻变了）
 * @param ruleId           规则 ID
 * @param metric           指标名
 * @param baselineVersion  当前规则版本（候选为新增规则时为 null）
 * @param candidateVersion 候选试算版本（规则被删除/仅当前侧有时为 null）
 * @param baselineEpisode  当前规则下的告警段；type=NEW 时为 null
 * @param candidateEpisode 候选规则下的告警段；type=REMOVED 时为 null
 * @param detail           人类可读的差异摘要（触发时刻差、持续时长差等）
 */
public record EpisodeDiff(Type type,
                          String ruleId,
                          String metric,
                          Integer baselineVersion,
                          Integer candidateVersion,
                          TrialEpisode baselineEpisode,
                          TrialEpisode candidateEpisode,
                          String detail) {

    public enum Type {
        NEW, REMOVED, CHANGED
    }
}
