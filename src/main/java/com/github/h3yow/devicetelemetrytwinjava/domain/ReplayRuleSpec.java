package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 试算用的候选规则：可以是尚未正式生效的阈值/算子组合。
 * 与 {@link AlertRule} 解耦——候选规则不进入规则存储、不影响线上判定。
 *
 * @param ruleId    候选规则标识（与线上规则同 ruleId 时，对比按此关联）
 * @param metric    判定指标名
 * @param operator  比较算子
 * @param threshold 候选阈值
 * @param enabled   是否启用（停用的候选规则在回放中不产生告警，与线上语义一致）
 */
public record ReplayRuleSpec(String ruleId,
                             String metric,
                             AlertRule.Operator operator,
                             double threshold,
                             boolean enabled) {
}
