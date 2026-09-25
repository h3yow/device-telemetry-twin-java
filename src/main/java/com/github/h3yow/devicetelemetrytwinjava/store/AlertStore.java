package com.github.h3yow.devicetelemetrytwinjava.store;

import com.github.h3yow.devicetelemetrytwinjava.domain.Alert;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertView;
import com.github.h3yow.devicetelemetrytwinjava.domain.EvaluationRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警状态存储：规则（带版本）、告警实体与只追加的判定历史。
 * <p>
 * 与 {@link ShadowStore} 一样，单设备维度的所有方法调用必须由调用方在
 * {@link DeviceLocks#forDevice(String)} 锁保护下进行，从而保证“影子更新 + 告警判定”
 * 在同一临界区内原子完成，并发上报不会产生重复告警或漏报。
 */
@Component
public class AlertStore {

    /** ruleId -> 当前规则（版本最高） */
    private final Map<String, AlertRule> rules = new HashMap<>();
    /** ruleId -> 该规则最近一条尚未 RESOLVED 的告警；恢复后清空，再次越限即产生新告警 */
    private final Map<String, Alert> openAlertsByRule = new HashMap<>();
    /** alertId -> 全部告警（含已恢复） */
    private final Map<String, Alert> alertsById = new HashMap<>();
    /** 只追加的判定历史 */
    private final List<EvaluationRecord> history = new ArrayList<>();

    private long alertSequence;

    // ---------- 规则管理 ----------

    /**
     * 创建或变更规则：相同 ruleId 的再次下发会产生 ruleVersion+1 的新版本；
     * 已存在的活动告警仍保留旧版本号，历史评估记录也不受影响，从而可复现历史判定。
     */
    public AlertRule putRule(String ruleId,
                             String deviceId,
                             String metric,
                             AlertRule.Operator operator,
                             double threshold,
                             boolean enabled,
                             long now) {
        AlertRule existing = rules.get(ruleId);
        int version = existing == null ? 1 : existing.ruleVersion() + 1;
        AlertRule rule = new AlertRule(ruleId, deviceId, metric, operator, threshold, version, now, enabled);
        rules.put(ruleId, rule);
        return rule;
    }

    public AlertRule getRule(String ruleId) {
        return rules.get(ruleId);
    }

    public List<AlertRule> rulesForDevice(String deviceId) {
        return rules.values().stream().filter(r -> r.deviceId().equals(deviceId)).toList();
    }

    public List<AlertRule> allRules() {
        return List.copyOf(rules.values());
    }

    // ---------- 告警生命周期（由 AlertService 在设备锁内调用） ----------

    public Alert openAlert(String ruleId) {
        return openAlertsByRule.get(ruleId);
    }

    public Alert createAlert(AlertRule rule, double value, long now) {
        String alertId = "alert-" + (++alertSequence);
        Alert alert = new Alert(alertId, rule, value, now);
        openAlertsByRule.put(rule.ruleId(), alert);
        alertsById.put(alertId, alert);
        return alert;
    }

    public void clearOpenAlert(String ruleId) {
        openAlertsByRule.remove(ruleId);
    }

    public AlertView alert(String alertId) {
        Alert a = alertsById.get(alertId);
        return a == null ? null : a.snapshot();
    }

    public List<AlertView> alertsForDevice(String deviceId) {
        return alertsById.values().stream()
                .filter(a -> a.deviceId().equals(deviceId))
                .map(Alert::snapshot)
                .toList();
    }

    public List<AlertView> allAlerts() {
        return alertsById.values().stream().map(Alert::snapshot).toList();
    }

    // ---------- 判定历史 ----------

    public void appendHistory(EvaluationRecord record) {
        history.add(record);
    }

    public List<EvaluationRecord> historyForDevice(String deviceId) {
        return history.stream().filter(r -> r.deviceId().equals(deviceId)).toList();
    }

    public List<EvaluationRecord> historyForRule(String ruleId) {
        return history.stream().filter(r -> r.ruleId().equals(ruleId)).toList();
    }

    public List<EvaluationRecord> allHistory() {
        return List.copyOf(history);
    }
}
