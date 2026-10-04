package com.github.h3yow.devicetelemetrytwinjava.store;

import com.github.h3yow.devicetelemetrytwinjava.domain.Alert;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertView;
import com.github.h3yow.devicetelemetrytwinjava.domain.EvaluationRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警状态存储：规则（带版本）、告警实体与只追加的判定历史。
 * <p>
 * 单设备维度的状态机不变式（同一规则同时只有一条 open 告警、影子更新与告警判定原子完成）
 * 仍由调用方持有的 {@link DeviceLocks#forDevice(String)} 设备锁保证。
 * 但本 store 的数据结构是跨设备共享的，而锁粒度是每设备一把，
 * 因此所有共享结构必须使用并发容器，避免多设备并发写入时丢记录或读到中间态：
 * <ul>
 *   <li>rules / openAlertsByRule / alertsById：{@link ConcurrentHashMap}；</li>
 *   <li>history：同步块保护的只追加列表，查询在锁内拷贝；</li>
 *   <li>alertSequence：{@link AtomicLong}，告警 ID 全局唯一；</li>
 *   <li>putRule 的版本递增用 {@link Map#compute} 原子完成，并发改规则不丢版本。</li>
 * </ul>
 */
@Component
public class AlertStore {

    /** ruleId -> 当前规则（版本最高） */
    private final Map<String, AlertRule> rules = new ConcurrentHashMap<>();
    /** ruleId -> 该规则最近一条尚未 RESOLVED 的告警；恢复后清空，再次越限即产生新告警 */
    private final Map<String, Alert> openAlertsByRule = new ConcurrentHashMap<>();
    /** alertId -> 全部告警（含已恢复） */
    private final Map<String, Alert> alertsById = new ConcurrentHashMap<>();
    /** 只追加的判定历史（由 historyLock 保护；跨设备并发追加不丢记录） */
    private final List<EvaluationRecord> history = new ArrayList<>();
    private final Object historyLock = new Object();

    private final AtomicLong alertSequence = new AtomicLong();

    // ---------- 规则管理 ----------

    /**
     * 创建或变更规则：相同 ruleId 的再次下发会产生 ruleVersion+1 的新版本；
     * 已存在的活动告警仍保留旧版本号，历史评估记录也不受影响，从而可复现历史判定。
     * 版本递增与写入在同一个原子 compute 中完成，并发下发同一 ruleId 不会丢版本。
     */
    public AlertRule putRule(String ruleId,
                             String deviceId,
                             String metric,
                             AlertRule.Operator operator,
                             double threshold,
                             boolean enabled,
                             long now) {
        AlertRule[] created = new AlertRule[1];
        rules.compute(ruleId, (id, existing) -> {
            int version = existing == null ? 1 : existing.ruleVersion() + 1;
            created[0] = new AlertRule(ruleId, deviceId, metric, operator, threshold, version, now, enabled);
            return created[0];
        });
        return created[0];
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
        String alertId = "alert-" + alertSequence.incrementAndGet();
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
        synchronized (historyLock) {
            history.add(record);
        }
    }

    public List<EvaluationRecord> historyForDevice(String deviceId) {
        synchronized (historyLock) {
            return history.stream().filter(r -> r.deviceId().equals(deviceId)).toList();
        }
    }

    public List<EvaluationRecord> historyForRule(String ruleId) {
        synchronized (historyLock) {
            return history.stream().filter(r -> r.ruleId().equals(ruleId)).toList();
        }
    }

    public List<EvaluationRecord> allHistory() {
        synchronized (historyLock) {
            return List.copyOf(history);
        }
    }
}
