package com.github.h3yow.devicetelemetrytwinjava.domain;

import java.util.Objects;

/**
 * 试算候选规则。是一份“尚未正式生效”的规则草案：改阈值、换算子、加新规则、停用
 * 都通过候选规则表达。候选规则<b>不会</b>写入线上 {@code AlertStore}，仅参与试算。
 *
 * @param ruleId    规则 ID；与当前生效规则 ruleId 相同即视为对该规则的修改草案
 * @param deviceId  适用设备 ID
 * @param metric    判定指标名
 * @param operator  比较算子
 * @param threshold 候选阈值
 * @param enabled   候选启用状态（false = 草案中停用）
 */
public record TrialRule(String ruleId,
                        String deviceId,
                        String metric,
                        AlertRule.Operator operator,
                        double threshold,
                        boolean enabled) {

    /** 按候选规则生成一个指定版本号的正式规则对象（仅试算期间使用，不落库）。 */
    public AlertRule toAlertRule(int ruleVersion, long createdAt) {
        return new AlertRule(ruleId, deviceId, metric, operator, threshold,
                ruleVersion, createdAt, enabled);
    }

    /** 与一条线上规则的判定语义（指标/算子/阈值/启用）是否一致。 */
    public boolean sameSemanticsAs(AlertRule rule) {
        return Objects.equals(metric, rule.metric())
                && operator == rule.operator()
                && Double.compare(threshold, rule.threshold()) == 0
                && enabled == rule.enabled();
    }
}
