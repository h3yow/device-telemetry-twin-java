package com.github.h3yow.devicetelemetrytwinjava.domain;

import java.util.List;

/**
 * 一组规则在指定历史窗口上的一次确定性回放结果。
 *
 * @param ruleSetId    规则集标识：{@code baseline}（当前生效规则）或 {@code candidate}（候选规则）
 * @param rules        本次回放实际使用的规则（不可变快照，按 ruleId 排序）
 * @param pointCount   窗口内参与判定的历史点数
 * @param episodes     按 ruleId、触发时刻、seq 排好序的告警段（未恢复段最后）
 */
public record TrialRun(String ruleSetId,
                       List<AlertRule> rules,
                       int pointCount,
                       List<TrialEpisode> episodes) {
}
