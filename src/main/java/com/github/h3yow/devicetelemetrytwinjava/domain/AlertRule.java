package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 阈值告警规则。规则不可变；变更规则 = 生成新 {@code ruleVersion} 的新规则对象，
 * 历史告警与历史评估记录保留其判定时的规则版本，以支持复现。
 *
 * @param ruleId      规则 ID
 * @param deviceId    适用设备 ID
 * @param metric      判定指标名
 * @param operator    比较算子
 * @param threshold   阈值
 * @param ruleVersion 规则版本，从 1 起单调递增
 * @param createdAt   规则生效时间（epoch millis）
 * @param enabled     是否启用（停用等价于抑制，不再产生新告警）
 */
public record AlertRule(String ruleId,
                        String deviceId,
                        String metric,
                        Operator operator,
                        double threshold,
                        int ruleVersion,
                        long createdAt,
                        boolean enabled) {

    public enum Operator {
        GT, GTE, LT, LTE, EQ;

        public boolean test(double value, double threshold) {
            return switch (this) {
                case GT -> value > threshold;
                case GTE -> value >= threshold;
                case LT -> value < threshold;
                case LTE -> value <= threshold;
                case EQ -> Double.compare(value, threshold) == 0;
            };
        }
    }

    /** 该规则是否对给定指标值越限（触发条件成立）。 */
    public boolean violatedBy(double value) {
        return operator.test(value, threshold);
    }
}
