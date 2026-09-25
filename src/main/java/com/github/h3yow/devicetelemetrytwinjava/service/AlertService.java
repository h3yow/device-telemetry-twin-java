package com.github.h3yow.devicetelemetrytwinjava.service;

import com.github.h3yow.devicetelemetrytwinjava.domain.Alert;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertView;
import com.github.h3yow.devicetelemetrytwinjava.domain.EvaluationRecord;
import com.github.h3yow.devicetelemetrytwinjava.store.AlertStore;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceLocks;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 阈值告警服务：规则版本管理 + 告警生命周期（触发 / 抑制 / 恢复）状态机。
 * <p>
 * 所有判定均在调用方持有的“设备锁”内执行，与影子写入处于同一临界区，
 * 保证并发上报下不会产生重复告警（同一规则同时只有一条 open 告警）或漏报。
 */
@Service
public class AlertService {

    private final AlertStore alertStore;
    private final DeviceRegistry deviceRegistry;
    private final DeviceLocks deviceLocks;

    public AlertService(AlertStore alertStore, DeviceRegistry deviceRegistry, DeviceLocks deviceLocks) {
        this.alertStore = alertStore;
        this.deviceRegistry = deviceRegistry;
        this.deviceLocks = deviceLocks;
    }

    /** 抑制操作结果。 */
    public record SuppressionResult(boolean applied, String reason, AlertView alertView) {
    }

    // ---------- 规则管理 ----------

    /**
     * 创建或更新规则。重复下发同 ruleId 即产生新版本；历史告警与评估记录保持旧版本信息。
     */
    public AlertRule putRule(String ruleId,
                             String deviceId,
                             String metric,
                             AlertRule.Operator operator,
                             double threshold,
                             boolean enabled) {
        if (!deviceRegistry.exists(deviceId)) {
            throw new IllegalArgumentException("设备不存在: " + deviceId);
        }
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("指标名不能为空");
        }
        if (!Double.isFinite(threshold)) {
            throw new IllegalArgumentException("阈值必须为有限数值");
        }
        return alertStore.putRule(ruleId, deviceId, metric, operator, threshold, enabled,
                System.currentTimeMillis());
    }

    public AlertRule getRule(String ruleId) {
        return alertStore.getRule(ruleId);
    }

    public List<AlertRule> rulesForDevice(String deviceId) {
        return alertStore.rulesForDevice(deviceId);
    }

    // ---------- 生命周期操作 ----------

    /**
     * 对某条规则当前的 open 告警施加抑制窗口（毫秒）。
     * 仅 ACTIVE 告警可被抑制；无活动告警时返回 {@code applied=false} 且给出原因，不静默成功。
     */
    public SuppressionResult suppress(String ruleId, long durationMillis) {
        return suppress(ruleId, durationMillis, System.currentTimeMillis());
    }

    /** 与 {@link #suppress(String, long)} 相同，但显式给出当前时刻（测试可复现）。 */
    public SuppressionResult suppress(String ruleId, long durationMillis, long now) {
        AlertRule rule = alertStore.getRule(ruleId);
        if (rule == null) {
            return new SuppressionResult(false, "规则不存在: " + ruleId, null);
        }
        if (durationMillis <= 0) {
            return new SuppressionResult(false, "抑制时长必须为正数: " + durationMillis, null);
        }
        ReentrantLock lock = deviceLocks.forDevice(rule.deviceId());
        lock.lock();
        try {
            Alert open = alertStore.openAlert(ruleId);
            if (open == null) {
                return new SuppressionResult(false, "规则没有活动告警，无可抑制对象", null);
            }
            if (open.state() == com.github.h3yow.devicetelemetrytwinjava.domain.AlertState.SUPPRESSED) {
                return new SuppressionResult(false, "告警已处于抑制中（直至 "
                        + open.suppressedUntil() + "）", open.snapshot());
            }
            open.suppress(now + durationMillis, now);
            return new SuppressionResult(true,
                    "告警已抑制至 " + (now + durationMillis), open.snapshot());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 对设备本次已接受的指标做全量规则判定，并追加只增历史。
     * <b>调用方必须持有该设备的设备锁。</b>
     *
     * @param metrics 已通过清洗、写入影子的指标
     * @param now     判定时刻（取上报接收时间，保证测试可复现）
     */
    public List<EvaluationRecord> evaluate(String deviceId,
                                           java.util.Map<String, Double> metrics,
                                           long now) {
        List<EvaluationRecord> records = new ArrayList<>();
        for (AlertRule rule : alertStore.rulesForDevice(deviceId)) {
            EvaluationRecord rec = evaluateOne(rule, metrics, now);
            // now 作为 evaluatedAt，保证用固定时钟测试时历史可复现
            records.add(rec);
            alertStore.appendHistory(rec);
        }
        return records;
    }

    private EvaluationRecord evaluateOne(AlertRule rule,
                                         java.util.Map<String, Double> metrics,
                                         long now) {
        if (!rule.enabled()) {
            // 停用规则不驱动状态机，但仍留一条判定记录，使停用期间的历史可追溯/可复现
            return record(rule, 0d, false,
                    com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict.RULE_DISABLED, null, now);
        }
        Double value = metrics.get(rule.metric());
        if (value == null) {
            return record(rule, 0d, false,
                    com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict.METRIC_ABSENT, null, now);
        }

        boolean violated = rule.violatedBy(value);
        Alert open = alertStore.openAlert(rule.ruleId());

        if (violated) {
            if (open == null) {
                Alert created = alertStore.createAlert(rule, value, now);
                return record(rule, value, true,
                        com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict.TRIGGERED,
                        created.alertId(), now);
            }
            return switch (open.state()) {
                case ACTIVE -> {
                    open.markStillActive(now);
                    yield record(rule, value, true,
                            com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict.STILL_ACTIVE,
                            open.alertId(), now);
                }
                case SUPPRESSED -> {
                    if (open.suppressedUntil() != null && now < open.suppressedUntil()) {
                        open.markStillSuppressed(now);
                        yield record(rule, value, true,
                                com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict.SUPPRESSED,
                                open.alertId(), now);
                    }
                    // 抑制窗口到期且仍越限 → 自动恢复活动
                    open.reactivate(now);
                    yield record(rule, value, true,
                            com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict.STILL_ACTIVE,
                            open.alertId(), now);
                }
                case RESOLVED -> {
                    // 防御：open 索引理应不包含已恢复告警；出现则视为新触发
                    Alert created = alertStore.createAlert(rule, value, now);
                    yield record(rule, value, true,
                            com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict.TRIGGERED,
                            created.alertId(), now);
                }
            };
        }

        // 未越限
        if (open == null) {
            return record(rule, value, false,
                    com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict.STILL_RESOLVED, null, now);
        }
        open.resolve(value, now);
        alertStore.clearOpenAlert(rule.ruleId());
        return record(rule, value, false,
                com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict.RESOLVED, open.alertId(), now);
    }

    private static EvaluationRecord record(AlertRule rule,
                                           double value,
                                           boolean violated,
                                           com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict verdict,
                                           String alertId,
                                           long now) {
        return new EvaluationRecord(rule.deviceId(), rule.ruleId(), rule.ruleVersion(),
                rule.operator(), rule.threshold(), rule.metric(), value, violated, verdict,
                alertId, now);
    }

    // ---------- 查询 ----------

    public List<AlertView> alertsForDevice(String deviceId) {
        return alertStore.alertsForDevice(deviceId);
    }

    public AlertView alert(String alertId) {
        return alertStore.alert(alertId);
    }

    public List<EvaluationRecord> historyForDevice(String deviceId) {
        return alertStore.historyForDevice(deviceId);
    }

    public List<EvaluationRecord> historyForRule(String ruleId) {
        return alertStore.historyForRule(ruleId);
    }
}
