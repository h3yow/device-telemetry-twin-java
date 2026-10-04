package com.github.h3yow.devicetelemetrytwinjava.store;

import com.github.h3yow.devicetelemetrytwinjava.domain.Alert;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertView;
import com.github.h3yow.devicetelemetrytwinjava.domain.EvaluationRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警状态存储：规则（带版本）、告警实体与只追加的判定历史。
 * <p>
 * 与 {@link ShadowStore} 一样，单设备维度的所有方法调用必须由调用方在
 * {@link DeviceLocks#forDevice(String)} 锁保护下进行，从而保证“影子更新 + 告警判定”
 * 在同一临界区内原子完成，并发上报不会产生重复告警或漏报。
 */
@Component
public class AlertStore {

    /** ruleId -> 当前规则（版本最高）。并发读多写少（规则可在上报期间变更），用并发映射。 */
    private final Map<String, AlertRule> rules = new ConcurrentHashMap<>();
    /** ruleId -> 该规则最近一条尚未 RESOLVED 的告警；恢复后清空，再次越限即产生新告警 */
    private final Map<String, Alert> openAlertsByRule = new ConcurrentHashMap<>();
    /** alertId -> 全部告警（含已恢复） */
    private final Map<String, Alert> alertsById = new ConcurrentHashMap<>();
    /**
     * 只追加的判定历史。上报高并发写入、查询/试算并发遍历；用同步列表保证追加 O(1)，
     * 对外读取统一先拷贝快照，遍历永不与追加互相干扰。
     */
    private final List<EvaluationRecord> history =
            java.util.Collections.synchronizedList(new ArrayList<>());

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
        // compute 原子完成“取当前版本 → +1 → 落库”，并发改规则也不会丢版本号
        return rules.compute(ruleId, (id, existing) -> {
            int version = existing == null ? 1 : existing.ruleVersion() + 1;
            return new AlertRule(id, deviceId, metric, operator, threshold, version, now, enabled);
        });
    }

    public AlertRule getRule(String ruleId) {
        return rules.get(ruleId);
    }

    /** 返回设备规则的不可变列表；按 ruleId 排序，保证判定顺序与试算结果确定。 */
    public List<AlertRule> rulesForDevice(String deviceId) {
        return rules.values().stream()
                .filter(r -> r.deviceId().equals(deviceId))
                .sorted(Comparator.comparing(AlertRule::ruleId))
                .toList();
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

    /** 原子拷贝一份历史快照，供过滤/遍历（追加与读取互不阻塞、不抛并发修改异常）。 */
    private List<EvaluationRecord> historySnapshot() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public List<EvaluationRecord> historyForDevice(String deviceId) {
        return historySnapshot().stream().filter(r -> r.deviceId().equals(deviceId)).toList();
    }

    public List<EvaluationRecord> historyForRule(String ruleId) {
        return historySnapshot().stream().filter(r -> r.ruleId().equals(ruleId)).toList();
    }

    public List<EvaluationRecord> allHistory() {
        return historySnapshot();
    }
}
