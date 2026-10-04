package com.github.h3yow.devicetelemetrytwinjava.service;

import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryReport;
import com.github.h3yow.devicetelemetrytwinjava.domain.TrialEpisode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 试算回放引擎：在一份<b>本地、临时</b>的告警状态上，按时间顺序对历史遥测逐点判定，
 * 产出完整告警段（触发→恢复）。纯函数式、确定性：
 * <ul>
 *   <li>不读写任何线上存储（影子/告警/判定历史均不受影响）；</li>
 *   <li>不使用当前时钟、随机数或锁，结果只取决于（历史点序列, 规则集）；</li>
 *   <li>告警段状态机语义与线上 {@code AlertService} 一致（触发/持续/恢复），
 *       但不含人工抑制（抑制是线上操作，试算只回答“规则本身会报什么”）。</li>
 * </ul>
 * 初始状态固定为“窗口起点无未决告警”。
 */
final class TrialEngine {

    private TrialEngine() {
    }

    /** 某条规则在回放期间的临时状态。 */
    private static final class OpenState {
        private long triggeredAt;
        private double triggerValue;
        private double peakValue;
        private long startTs;
        private int sampleCount;
    }

    /**
     * 对一组规则回放一批历史点。
     *
     * @param rules        规则集（调用方应已按 ruleId 排序去重）
     * @param points       按时间顺序的历史遥测
     * @param ruleSetId    结果标识（baseline / candidate）
     * @return 确定性的回放结果（告警段已排序）
     */
    static List<TrialEpisode> replay(List<AlertRule> rules, List<TelemetryReport> points) {
        List<TrialEpisode> episodes = new ArrayList<>();
        Map<String, OpenState> open = new HashMap<>();

        for (TelemetryReport point : points) {
            Map<String, Double> metrics = point.metrics();
            long evaluatedAt = point.receivedAt();
            for (AlertRule rule : rules) {
                if (!rule.enabled()) {
                    continue; // 停用规则不驱动状态机（与线上 RULE_DISABLED 语义一致：不产生告警）
                }
                Double value = metrics.get(rule.metric());
                if (value == null) {
                    continue; // 本次上报不含该指标（METRIC_ABSENT）
                }
                boolean violated = rule.violatedBy(value);
                OpenState state = open.get(rule.ruleId());
                if (violated) {
                    if (state == null) {
                        state = new OpenState();
                        state.triggeredAt = evaluatedAt;
                        state.triggerValue = value;
                        state.peakValue = value;
                        state.startTs = point.ts();
                        state.sampleCount = 1;
                        open.put(rule.ruleId(), state);
                    } else {
                        state.sampleCount++;
                        if (moreExtreme(rule, value, state.peakValue)) {
                            state.peakValue = value;
                        }
                    }
                } else if (state != null) {
                    // 回到阈值内：闭合本段
                    episodes.add(toEpisode(rule, state, evaluatedAt, value, point.ts(), episodes));
                    open.remove(rule.ruleId());
                }
            }
        }

        // 窗口结束仍未恢复的段：以 open 段输出（resolvedAt=null）
        for (AlertRule rule : rules) {
            OpenState state = open.get(rule.ruleId());
            if (state != null) {
                episodes.add(toEpisode(rule, state, null, Double.NaN, null, episodes));
            }
        }

        // 确定性排序：ruleId、触发时刻、段序号
        episodes.sort(Comparator.comparing(TrialEpisode::ruleId)
                .thenComparingLong(TrialEpisode::triggeredAt)
                .thenComparingInt(TrialEpisode::seq));
        return episodes;
    }

    /** 按算子方向判断新值是否比当前峰值更极端（用于段内 peak 统计，便于复核）。 */
    private static boolean moreExtreme(AlertRule rule, double candidate, double currentPeak) {
        return switch (rule.operator()) {
            case GT, GTE -> candidate > currentPeak;
            case LT, LTE -> candidate < currentPeak;
            case EQ -> false;
        };
    }

    private static TrialEpisode toEpisode(AlertRule rule, OpenState state,
                                          Long resolvedAt, double resolveValue, Long endTs,
                                          List<TrialEpisode> existing) {
        int seq = (int) existing.stream().filter(e -> e.ruleId().equals(rule.ruleId())).count() + 1;
        return new TrialEpisode(rule.ruleId(), rule.ruleVersion(), rule.operator(), rule.threshold(),
                rule.metric(), seq, state.triggeredAt, state.triggerValue, state.peakValue,
                state.sampleCount, resolvedAt, resolveValue, state.startTs, endTs);
    }
}
