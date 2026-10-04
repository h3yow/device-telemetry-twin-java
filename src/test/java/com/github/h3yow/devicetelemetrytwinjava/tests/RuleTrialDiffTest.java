package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertState;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertView;
import com.github.h3yow.devicetelemetrytwinjava.domain.Device;
import com.github.h3yow.devicetelemetrytwinjava.domain.EpisodeDiff;
import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryReport;
import com.github.h3yow.devicetelemetrytwinjava.domain.TrialCoverage;
import com.github.h3yow.devicetelemetrytwinjava.domain.TrialEpisode;
import com.github.h3yow.devicetelemetrytwinjava.domain.TrialRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.WhatIfResult;
import com.github.h3yow.devicetelemetrytwinjava.service.AlertService;
import com.github.h3yow.devicetelemetrytwinjava.service.TelemetryService;
import com.github.h3yow.devicetelemetrytwinjava.service.TrialService;
import com.github.h3yow.devicetelemetrytwinjava.store.AlertStore;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceLocks;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceRegistry;
import com.github.h3yow.devicetelemetrytwinjava.store.ShadowStore;
import com.github.h3yow.devicetelemetrytwinjava.store.TelemetryHistoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则试算差异对照与可复现：
 * 候选规则（改阈值 / 加规则）相对当前生效规则的 NEW / REMOVED / CHANGED 差异正确，
 * 且同一份历史 + 同一份候选规则重复试算结果完全一致。
 */
class RuleTrialDiffTest {

    private static final Logger log = LoggerFactory.getLogger(RuleTrialDiffTest.class);
    private static final String DEVICE = "dev-trial";
    private static final String RULE = "rule-temp";

    private DeviceRegistry registry;
    private TelemetryService telemetryService;
    private AlertService alertService;
    private AlertStore alertStore;
    private TrialService trialService;
    private long clock;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        ShadowStore shadowStore = new ShadowStore();
        DeviceLocks locks = new DeviceLocks();
        alertStore = new AlertStore();
        TelemetryHistoryStore historyStore = new TelemetryHistoryStore(20000, 200000);
        alertService = new AlertService(alertStore, registry, locks);
        telemetryService = new TelemetryService(registry, shadowStore, locks, alertService,
                historyStore, 5000, 60000);
        trialService = new TrialService(historyStore, alertStore, registry);
        registry.register(new Device(DEVICE, "试算设备",
                Map.of("temperature", new Device.MetricBounds(-40, 85),
                        "humidity", new Device.MetricBounds(0, 100))));
        // 当前生效规则：temperature > 30
        alertService.putRule(RULE, DEVICE, "temperature", AlertRule.Operator.GT, 30.0, true);
        clock = 10_000_000L;
    }

    private long send(double temp) {
        clock += 1000;
        telemetryService.ingestWithAlerts(new TelemetryReport(
                DEVICE, -1L, clock, Map.of("temperature", temp), clock));
        log.info("[输入] ts={} temperature={}", clock, temp);
        return clock;
    }

    private long sendTempAndHumidity(double temp, double humidity) {
        clock += 1000;
        telemetryService.ingestWithAlerts(new TelemetryReport(
                DEVICE, -1L, clock, Map.of("temperature", temp, "humidity", humidity), clock));
        log.info("[输入] ts={} temperature={} humidity={}", clock, temp, humidity);
        return clock;
    }

    private TrialRule candidateTempGt(double threshold) {
        return new TrialRule(RULE, DEVICE, "temperature", AlertRule.Operator.GT, threshold, true);
    }

    @Test
    @DisplayName("阈值放宽 30->40：原有的两次短暂越限告警被取消(REMOVED)，差异携带两侧版本与输入值")
    void thresholdRelaxedProducesRemoved() {
        long t1 = send(35);  // 基线触发，候选不触发
        long t2 = send(20);
        long t3 = send(35);  // 基线再次触发，候选不触发
        long t4 = send(20);

        WhatIfResult r = trialService.runWhatIf(DEVICE, t1, t4,
                List.of(candidateTempGt(40.0)));

        assertEquals(TrialCoverage.COMPLETE, r.coverage());
        assertEquals(2, r.baseline().episodes().size(), "基线 >30 应有两段告警");
        assertEquals(0, r.candidate().episodes().size(), "候选 >40 不应有告警");
        List<EpisodeDiff> removed = r.diffs().stream()
                .filter(d -> d.type() == EpisodeDiff.Type.REMOVED).toList();
        assertEquals(2, removed.size(), "应得到两条 REMOVED 差异");
        for (EpisodeDiff d : removed) {
            assertEquals(1, d.baselineVersion(), "差异必须能说清基线规则版本");
            assertEquals(2, d.candidateVersion(), "差异必须能说清候选规则版本");
            assertNotNull(d.baselineEpisode());
            assertNull(d.candidateEpisode());
            assertEquals(35.0, d.baselineEpisode().triggerValue(), 0.0001,
                    "差异必须能说清触发输入值");
            log.info("[差异 REMOVED] rule={} {} 触发ts={} 值={}", d.ruleId(), d.detail(),
                    d.baselineEpisode().triggeredAt(), d.baselineEpisode().triggerValue());
        }
    }

    @Test
    @DisplayName("新增一条湿度规则：原无告警的窗口出现全新告警段(NEW)")
    void addedRuleProducesNew() {
        long t1 = send(35);
        send(20);
        long t3 = sendTempAndHumidity(20.0, 5.0); // 温度不越限，湿度 <10 越限
        long t4 = send(20);

        TrialRule humidityLow = new TrialRule("rule-hum-low", DEVICE, "humidity",
                AlertRule.Operator.LT, 10.0, true);
        WhatIfResult r = trialService.runWhatIf(DEVICE, t1, t4,
                List.of(candidateTempGt(30.0), humidityLow));

        List<EpisodeDiff> added = r.diffs().stream()
                .filter(d -> d.type() == EpisodeDiff.Type.NEW).toList();
        assertEquals(1, added.size(), "新增湿度规则应带来一条 NEW 差异");
        EpisodeDiff d = added.get(0);
        assertEquals("rule-hum-low", d.ruleId());
        assertNull(d.baselineVersion(), "新增规则无基线版本");
        assertEquals(0, d.candidateVersion(), "新增规则候选版本为试算初版 0");
        assertEquals(5.0, d.candidateEpisode().triggerValue(), 0.0001);
        log.info("[差异 NEW] rule={} {}", d.ruleId(), d.detail());
    }

    @Test
    @DisplayName("阈值收紧导致触发时刻提前/恢复时刻变化：配对段标记 CHANGED")
    void triggerShiftProducesChanged() {
        long t1 = send(35);  // >30 触发；>32 同样触发（时刻相同，不构成时刻变化）
        long t2 = send(31);  // 基线仍越限；候选 >32 已恢复
        long t3 = send(20);  // 两侧都恢复
        // 基线段: [t1, t3)；候选段: [t1, t2)，恢复时刻不同 -> CHANGED

        WhatIfResult r = trialService.runWhatIf(DEVICE, t1, t3,
                List.of(candidateTempGt(32.0)));

        List<EpisodeDiff> changed = r.diffs().stream()
                .filter(d -> d.type() == EpisodeDiff.Type.CHANGED).toList();
        assertEquals(1, changed.size(), "恢复时刻不同应为一条 CHANGED");
        EpisodeDiff d = changed.get(0);
        assertEquals(t1, d.baselineEpisode().triggeredAt());
        assertEquals(t2, d.candidateEpisode().resolvedAt(), "候选提前在 t2 恢复");
        assertEquals(t3, d.baselineEpisode().resolvedAt(), "基线在 t3 才恢复");
        assertTrue(d.detail().contains("持续时长"), "差异摘要应说明持续时长变化: " + d.detail());
        log.info("[差异 CHANGED] rule={} {}", d.ruleId(), d.detail());
    }

    @Test
    @DisplayName("同一历史与同一候选规则重复试算结果逐字段一致（可复现）")
    void repeatedTrialsAreIdentical() {
        long t1 = send(35);
        send(20);
        long t3 = send(42);
        long t4 = send(20);

        TrialRule candidate = candidateTempGt(40.0);
        WhatIfResult first = trialService.runWhatIf(DEVICE, t1, t4, List.of(candidate));
        WhatIfResult second = trialService.runWhatIf(DEVICE, t1, t4, List.of(candidate));

        assertEquals(first, second, "重复试算必须得到一模一样的结果");
        // 具体可复现内容：基线段、候选段、差异列表的关键时间点
        assertEquals(2, first.baseline().episodes().size());
        assertEquals(1, first.candidate().episodes().size());
        TrialEpisode cand = first.candidate().episodes().get(0);
        assertEquals(t3, cand.triggeredAt());
        assertEquals(t4, cand.resolvedAt());
        assertEquals(1, first.diffs().size());
        assertEquals(EpisodeDiff.Type.REMOVED, first.diffs().get(0).type());
        log.info("[可复现] 两次试算结果相等 baseline段={} candidate段={} 差异={}",
                first.baseline().episodes().size(),
                first.candidate().episodes().size(), first.diffs().size());
    }

    @Test
    @DisplayName("试算完全只读：试算后线上告警状态、数量、判定历史均不变化")
    void trialDoesNotMutateOnlineState() {
        long t1 = send(35);  // 线上告警保持 ACTIVE（之后不回落）
        send(40);
        int historyBefore = alertService.historyForDevice(DEVICE).size();
        List<AlertView> alertsBefore = alertService.alertsForDevice(DEVICE);

        WhatIfResult r = trialService.runWhatIf(DEVICE, t1, clock,
                List.of(candidateTempGt(40.0)));
        assertNotNull(r);

        List<AlertView> alertsAfter = alertService.alertsForDevice(DEVICE);
        int historyAfter = alertService.historyForDevice(DEVICE).size();
        assertEquals(alertsBefore, alertsAfter, "试算不得改动线上告警");
        assertEquals(1, alertsAfter.size());
        assertEquals(AlertState.ACTIVE, alertsAfter.get(0).state(),
                "线上告警仍是 ACTIVE，未被试算的回落/恢复影响");
        assertEquals(historyBefore, historyAfter, "试算不得追加线上判定历史");
        log.info("[隔离] 试算前后线上告警数={} 历史条数={} 保持不变", alertsAfter.size(), historyAfter);
    }
}
