package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertState;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict;
import com.github.h3yow.devicetelemetrytwinjava.domain.Device;
import com.github.h3yow.devicetelemetrytwinjava.domain.EvaluationRecord;
import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryReport;
import com.github.h3yow.devicetelemetrytwinjava.service.AlertService;
import com.github.h3yow.devicetelemetrytwinjava.service.TelemetryService;
import com.github.h3yow.devicetelemetrytwinjava.store.AlertStore;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceLocks;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceRegistry;
import com.github.h3yow.devicetelemetrytwinjava.store.ShadowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则变更（阈值调整、停用）场景：历史评估记录保留判定时的规则版本/阈值，
 * 可据此离线复现历史判定；既有告警的 ruleVersion 不被追溯改写。
 */
class RuleChangeReproducibilityTest {

    private static final Logger log = LoggerFactory.getLogger(RuleChangeReproducibilityTest.class);
    private static final String DEVICE = "dev-rule";
    private static final String RULE = "rule-temp";

    private DeviceRegistry registry;
    private TelemetryService telemetryService;
    private AlertService alertService;
    private AlertStore alertStore;
    private long clock;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        ShadowStore shadowStore = new ShadowStore();
        DeviceLocks locks = new DeviceLocks();
        alertStore = new AlertStore();
        alertService = new AlertService(alertStore, registry, locks);
        telemetryService = new TelemetryService(registry, shadowStore, locks, alertService, 5000, 60000);
        registry.register(new Device(DEVICE, "规则变更设备",
                Map.of("temperature", new Device.MetricBounds(-40, 85))));
        clock = 5_000_000L;
    }

    private EvaluationRecord send(long seq, double temp) {
        clock += 1000;
        var out = telemetryService.ingestWithAlerts(new TelemetryReport(
                DEVICE, seq, clock, Map.of("temperature", temp), clock));
        EvaluationRecord rec = out.evaluations().isEmpty() ? null : out.evaluations().get(0);
        log.info("[输入] seq={} temp={} -> [判定] verdict={} violated={} ruleVersion={} threshold={}",
                seq, temp, rec == null ? "(无评估)" : rec.verdict(),
                rec == null ? "-" : rec.violated(),
                rec == null ? "-" : rec.ruleVersion(),
                rec == null ? "-" : rec.threshold());
        return rec;
    }

    @Test
    @DisplayName("阈值 30 触发告警 -> 阈值改 40 后历史仍可按 v1 复现，新判定按 v2 执行")
    void historyReproducibleAcrossRuleVersions() {
        // v1: >30 触发
        alertService.putRule(RULE, DEVICE, "temperature", AlertRule.Operator.GT, 30.0, true);
        EvaluationRecord r1 = send(1, 35);
        assertEquals(1, r1.ruleVersion());
        assertEquals(30.0, r1.threshold());
        assertEquals(AlertVerdict.TRIGGERED, r1.verdict());
        String firstAlertId = alertStore.openAlert(RULE).alertId();

        // 回落恢复
        send(2, 20);
        assertEquals(AlertState.RESOLVED, alertService.alert(firstAlertId).state());

        // 变更规则 -> v2: >40
        AlertRule v2 = alertService.putRule(RULE, DEVICE, "temperature",
                AlertRule.Operator.GT, 40.0, true);
        assertEquals(2, v2.ruleVersion());

        // 35 在 v2 下不再越限
        EvaluationRecord r3 = send(3, 35);
        assertEquals(2, r3.ruleVersion());
        assertEquals(40.0, r3.threshold());
        assertEquals(AlertVerdict.STILL_RESOLVED, r3.verdict());
        assertTrue(alertService.alertsForDevice(DEVICE).size() == 1, "v2 未触发新告警");

        // 42 在 v2 下触发：新告警记录的是 v2
        EvaluationRecord r4 = send(4, 42);
        assertEquals(AlertVerdict.TRIGGERED, r4.verdict());
        var secondAlert = alertStore.openAlert(RULE).snapshot();
        assertEquals(2, secondAlert.ruleVersion(), "新告警必须挂在新版本上");
        assertNotEquals(firstAlertId, secondAlert.alertId());

        // 第一条告警仍保持 v1 语义，不被追溯改写
        var firstAlert = alertService.alert(firstAlertId);
        assertEquals(1, firstAlert.ruleVersion());
        assertEquals(AlertState.RESOLVED, firstAlert.state());

        // 历史复现：按每条记录自带的 operator/threshold 重新计算 violated，必须与记录一致
        List<EvaluationRecord> history = alertService.historyForRule(RULE);
        for (EvaluationRecord rec : history) {
            boolean recomputed = rec.operator().test(rec.value(), rec.threshold());
            assertEquals(rec.violated(), recomputed,
                    "历史判定不可复现: " + rec);
            // 状态机结论与 violated 自洽
            if (rec.verdict() == AlertVerdict.TRIGGERED || rec.verdict() == AlertVerdict.STILL_ACTIVE) {
                assertTrue(rec.violated(), "越限类结论要求 violated=true: " + rec);
            }
            if (rec.verdict() == AlertVerdict.RESOLVED || rec.verdict() == AlertVerdict.STILL_RESOLVED) {
                assertTrue(!rec.violated(), "恢复类结论要求 violated=false: " + rec);
            }
        }
        log.info("[规则变更后历史复现] 共 {} 条记录，全部可按记录中的版本/阈值重算", history.size());
    }

    @Test
    @DisplayName("规则停用后不再判定；重新启用为新版本，历史序列连续可追溯")
    void disableAndReenableRule() {
        alertService.putRule(RULE, DEVICE, "temperature", AlertRule.Operator.GT, 30.0, true);
        send(1, 50); // v1 TRIGGERED

        alertService.putRule(RULE, DEVICE, "temperature", AlertRule.Operator.GT, 30.0, false);
        send(2, 60); // 停用：无评估、不产生新告警
        assertEquals(1, alertService.alertsForDevice(DEVICE).size());

        AlertRule v3 = alertService.putRule(RULE, DEVICE, "temperature",
                AlertRule.Operator.GT, 30.0, true);
        assertEquals(3, v3.ruleVersion(), "停用也算一次规则变更，重新启用版本继续递增");

        // 老告警仍 open 且处于 ACTIVE：再越限只是 STILL_ACTIVE，不会重复触发
        EvaluationRecord rec = send(3, 70);
        assertEquals(3, rec.ruleVersion());
        assertEquals(AlertVerdict.STILL_ACTIVE, rec.verdict());
        assertEquals(1, alertService.alertsForDevice(DEVICE).size());
        log.info("[停用/重启用序列] {}", alertService.historyForRule(RULE));
    }
}
