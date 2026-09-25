package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 告警实体（可变）。所有读写都由 {@code AlertStore} 的同一把锁串行化，
 * 因此字段无需额外同步；对外只暴露不可变快照 {@link AlertView}。
 */
public class Alert {

    private final String alertId;
    private final String ruleId;
    private final String deviceId;
    private final String metric;
    private final int ruleVersion;

    private AlertState state;
    private double triggerValue;
    private long triggeredAt;
    private Long suppressedUntil;
    private Long resolvedAt;
    private double resolveValue;
    private long lastEvaluatedAt;
    private int triggerCount;

    public Alert(String alertId, AlertRule rule, double triggerValue, long now) {
        this.alertId = alertId;
        this.ruleId = rule.ruleId();
        this.deviceId = rule.deviceId();
        this.metric = rule.metric();
        this.ruleVersion = rule.ruleVersion();
        this.state = AlertState.ACTIVE;
        this.triggerValue = triggerValue;
        this.triggeredAt = now;
        this.lastEvaluatedAt = now;
        this.triggerCount = 1;
    }

    public String alertId() {
        return alertId;
    }

    public String ruleId() {
        return ruleId;
    }

    public String deviceId() {
        return deviceId;
    }

    public String metric() {
        return metric;
    }

    public int ruleVersion() {
        return ruleVersion;
    }

    public AlertState state() {
        return state;
    }

    public double triggerValue() {
        return triggerValue;
    }

    public long triggeredAt() {
        return triggeredAt;
    }

    public Long suppressedUntil() {
        return suppressedUntil;
    }

    public Long resolvedAt() {
        return resolvedAt;
    }

    public double resolveValue() {
        return resolveValue;
    }

    public long lastEvaluatedAt() {
        return lastEvaluatedAt;
    }

    public int triggerCount() {
        return triggerCount;
    }

    /** 进入抑制窗口：窗口内即使越限也保持 SUPPRESSED。 */
    public void suppress(long until, long now) {
        this.state = AlertState.SUPPRESSED;
        this.suppressedUntil = until;
        this.lastEvaluatedAt = now;
    }

    /** 活动告警上再次越限：仅更新评估时间与累计次数，不产生新告警。 */
    public void markStillActive(long now) {
        this.triggerCount++;
        this.lastEvaluatedAt = now;
    }

    /** 抑制窗口内仍越限。 */
    public void markStillSuppressed(long now) {
        this.lastEvaluatedAt = now;
    }

    /** 抑制窗口到期且仍越限：自动升级回活动告警。 */
    public void reactivate(long now) {
        this.state = AlertState.ACTIVE;
        this.suppressedUntil = null;
        this.triggerCount++;
        this.lastEvaluatedAt = now;
    }

    /** 回到阈值内：恢复告警，生命周期闭合。 */
    public void resolve(double value, long now) {
        this.state = AlertState.RESOLVED;
        this.suppressedUntil = null;
        this.resolveValue = value;
        this.resolvedAt = now;
        this.lastEvaluatedAt = now;
    }

    /** 生成对外不可变快照。 */
    public AlertView snapshot() {
        return new AlertView(alertId, ruleId, deviceId, metric, ruleVersion, state,
                triggerValue, triggeredAt, suppressedUntil, resolvedAt, resolveValue,
                lastEvaluatedAt, triggerCount);
    }
}
