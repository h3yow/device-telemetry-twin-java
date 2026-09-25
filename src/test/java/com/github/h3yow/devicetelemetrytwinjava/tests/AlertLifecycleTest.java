package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertState;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertView;
import com.github.h3yow.devicetelemetrytwinjava.domain.Device;
import com.github.h3yow.devicetelemetrytwinjava.domain.EvaluationRecord;
import com.github.h3yow.devicetelemetrytwinjava.domain.IngestStatus;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警生命周期：触发 / 持续越限不重复告警 / 抑制 / 抑制到期恢复活动 / 恢复闭合 / 停用规则。
 */
class AlertLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(AlertLifecycleTest.class);
    private static final String DEVICE = "dev-alert";
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
        registry.register(new Device(DEVICE, "告警测试设备",
                Map.of("temperature", new Device.MetricBounds(-40, 85))));
        alertService.putRule(RULE, DEVICE, "temperature", AlertRule.Operator.GT, 30.0, true);
        clock = 1_000_000L;
    }

    /** 用受控时钟发送一条遥测，seq 自动递增。 */
    private TelemetryService.IngestOutcome send(long seq, double temp) {
        clock += 1000;
        TelemetryReport r = new TelemetryReport(DEVICE, seq, clock,
                Map.of("temperature", temp), clock);
        var out = telemetryService.ingestWithAlerts(r);
        EvaluationRecord rec = out.evaluations().isEmpty() ? null : out.evaluations().get(0);
        log.info("[输入] seq={} temp={} -> [接入] {} [判定依据] {} (规则 {} {} {})",
                seq, temp, out.result().status(),
                rec == null ? "未判定" : rec.verdict() + " violated=" + rec.violated(),
                RULE, AlertRule.Operator.GT, 30.0);
        return out;
    }

    private AlertView openAlert() {
        return alertStore.openAlert(RULE) == null ? null : alertStore.openAlert(RULE).snapshot();
    }

    @Test
    @DisplayName("触发一次；持续越限只返回 STILL_ACTIVE 且不产生新告警；回落到阈值内 RESOLVED")
    void triggerPersistResolve() {
        var t1 = send(1, 35);
        assertEquals(IngestStatus.ACCEPTED, t1.result().status());
        assertEquals(AlertVerdict.TRIGGERED, t1.evaluations().get(0).verdict());
        AlertView a1 = openAlert();
        assertEquals(AlertState.ACTIVE, a1.state());
        assertEquals(1, alertService.alertsForDevice(DEVICE).size());

        var t2 = send(2, 36);
        assertEquals(AlertVerdict.STILL_ACTIVE, t2.evaluations().get(0).verdict());
        var t3 = send(3, 40);
        assertEquals(AlertVerdict.STILL_ACTIVE, t3.evaluations().get(0).verdict());
        assertEquals(1, alertService.alertsForDevice(DEVICE).size(), "持续越限不得产生重复告警");
        assertEquals(3, openAlert().triggerCount(), "STILL_ACTIVE 应累计触发次数");

        var t4 = send(4, 25);
        assertEquals(AlertVerdict.RESOLVED, t4.evaluations().get(0).verdict());
        List<AlertView> all = alertService.alertsForDevice(DEVICE);
        assertEquals(AlertState.RESOLVED, all.get(0).state());
        assertEquals(25.0, all.get(0).resolveValue());

        // 恢复后再越限：产生一条全新告警（新的 alertId）
        var t5 = send(5, 42);
        assertEquals(AlertVerdict.TRIGGERED, t5.evaluations().get(0).verdict());
        assertEquals(2, alertService.alertsForDevice(DEVICE).size(), "恢复后再越限必须产生新告警");
        log.info("[告警历史] {}", alertService.alertsForDevice(DEVICE));
    }

    @Test
    @DisplayName("抑制窗口内越限为 SUPPRESSED 不升级；窗口结束仍越限自动恢复 ACTIVE")
    void suppressLifecycle() {
        send(1, 35); // TRIGGERED
        String alertId = openAlert().alertId();

        var suppressed = alertService.suppress(RULE, 10_000, clock);
        assertTrue(suppressed.applied(), suppressed.reason());
        assertEquals(AlertState.SUPPRESSED, openAlert().state());

        var t2 = send(2, 40);
        assertEquals(AlertVerdict.SUPPRESSED, t2.evaluations().get(0).verdict());
        assertEquals(1, alertService.alertsForDevice(DEVICE).size(), "抑制期间不得产生新告警");
        assertEquals(alertId, openAlert().alertId());

        // 模拟抑制窗口到期：直接把时钟推过窗口后再上报
        clock += 20_000;
        var t3 = send(3, 41);
        assertEquals(AlertVerdict.STILL_ACTIVE, t3.evaluations().get(0).verdict());
        assertEquals(AlertState.ACTIVE, openAlert().state());
        assertEquals(alertId, openAlert().alertId(), "是同一条告警恢复活动，而非新告警");

        // 恢复
        send(4, 20);
        assertEquals(AlertState.RESOLVED, openAlert() == null ? AlertState.RESOLVED : openAlert().state());
        // 无活动告警时再次抑制应明确拒绝而非静默成功
        var suppressAgain = alertService.suppress(RULE, 1000);
        assertFalse(suppressAgain.applied());
        log.info("[无活动告警抑制结论] {}", suppressAgain.reason());
    }

    @Test
    @DisplayName("规则停用后上报为 RULE_DISABLED 且不产生告警；上报缺失监控指标得到 METRIC_ABSENT")
    void disabledRuleAndMissingMetric() {
        alertService.putRule(RULE, DEVICE, "temperature", AlertRule.Operator.GT, 30.0, false);
        // 35 在设备合法区间内、但超过停用规则阈值；停用下应为 RULE_DISABLED 而非触发
        var out = send(1, 35);
        assertEquals(AlertVerdict.RULE_DISABLED, out.evaluations().get(0).verdict());
        assertTrue(alertService.alertsForDevice(DEVICE).isEmpty());

        // 换一个只监控 humidity 的规则，发送不带 humidity 的上报
        alertService.putRule(RULE, DEVICE, "humidity", AlertRule.Operator.GT, 80.0, true);
        clock += 1000;
        var absent = telemetryService.ingestWithAlerts(new TelemetryReport(
                DEVICE, 2, clock, Map.of("temperature", 20.0), clock));
        assertEquals(AlertVerdict.METRIC_ABSENT, absent.evaluations().get(0).verdict());
        log.info("[停用/缺指标判定] disabled={}, absent={}",
                out.evaluations().get(0).verdict(),
                absent.evaluations().get(0).verdict());
    }
}
