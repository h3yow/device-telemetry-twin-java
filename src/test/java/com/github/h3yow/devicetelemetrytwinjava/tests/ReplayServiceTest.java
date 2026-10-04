package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict;
import com.github.h3yow.devicetelemetrytwinjava.domain.Device;
import com.github.h3yow.devicetelemetrytwinjava.domain.IngestStatus;
import com.github.h3yow.devicetelemetrytwinjava.domain.ReplayAlert;
import com.github.h3yow.devicetelemetrytwinjava.domain.ReplayDiff;
import com.github.h3yow.devicetelemetrytwinjava.domain.ReplayResult;
import com.github.h3yow.devicetelemetrytwinjava.domain.ReplayRuleSpec;
import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryReport;
import com.github.h3yow.devicetelemetrytwinjava.service.AlertService;
import com.github.h3yow.devicetelemetrytwinjava.service.ReplayService;
import com.github.h3yow.devicetelemetrytwinjava.service.TelemetryService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则试算：差异对照内容、结果可复现、试算与线上互不影响、淘汰可见性。
 */
class ReplayServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayServiceTest.class);
    private static final String DEVICE = "dev-replay";
    private static final String RULE = "rule-temp";

    private DeviceRegistry registry;
    private AlertStore alertStore;
    private AlertService alertService;
    private TelemetryHistoryStore historyStore;
    private TelemetryService telemetryService;
    private ReplayService replayService;
    private long clock;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        ShadowStore shadowStore = new ShadowStore();
        DeviceLocks locks = new DeviceLocks();
        alertStore = new AlertStore();
        alertService = new AlertService(alertStore, registry, locks);
        historyStore = new TelemetryHistoryStore(50000, 500000);
        telemetryService = new TelemetryService(registry, shadowStore, locks, alertService,
                historyStore, 5000, 60000);
        replayService = new ReplayService(historyStore, alertStore, registry, locks);
        registry.register(new Device(DEVICE, "试算测试设备",
                Map.of("temperature", new Device.MetricBounds(-40, 85))));
        clock = 1_000_000L;
    }

    private void send(long seq, double temp) {
        clock += 1000;
        var out = telemetryService.ingestWithAlerts(
                new TelemetryReport(DEVICE, seq, clock, Map.of("temperature", temp), clock));
        log.info("[输入] seq={} temp={} -> {} {}", seq, temp, out.result().status(),
                out.evaluations().isEmpty() ? "" : out.evaluations().get(0).verdict());
        assertEquals(IngestStatus.ACCEPTED, out.result().status());
    }

    /** 构造线上场景：t+1 越限 35 触发，t+2 越限 40 持续，t+3 回到 20 恢复。 */
    private void buildLiveScenario() {
        alertService.putRule(RULE, DEVICE, "temperature", AlertRule.Operator.GT, 30.0, true);
        send(1, 35.0);
        send(2, 40.0);
        send(3, 20.0);
    }

    @Test
    @DisplayName("差异对照：阈值调高→CHANGED/REMOVED，新规则→ADDED，且每条差异带版本与输入值")
    void diffContent() {
        buildLiveScenario();
        long from = 1_001_000L, to = clock; // 与首个留存点的接收时间对齐，窗口被完整覆盖

        // 候选1：同 ruleId 阈值 38 → 第一次 35 不再越限，触发点从 t+1 变为 t+2 → CHANGED
        // 候选2：全新规则 rule-low LT 25 → t+3 的 20 触发 → ADDED
        ReplayResult result = replayService.replay(DEVICE, from, to, List.of(
                new ReplayRuleSpec(RULE, "temperature", AlertRule.Operator.GT, 38.0, true),
                new ReplayRuleSpec("rule-low", "temperature", AlertRule.Operator.LT, 25.0, true)));
        log.info("[试算结果] coverage={} points={} candidateAlerts={} diffs={}",
                result.coverage(), result.pointsReplayed(), result.candidateAlerts(), result.diffs());

        assertEquals(ReplayResult.Coverage.FULL, result.coverage());
        assertEquals(3, result.pointsReplayed());
        assertEquals(1, result.liveAlertsInWindow().size());

        ReplayDiff changed = result.diffs().stream()
                .filter(d -> d.type() == ReplayDiff.Type.CHANGED && d.ruleId().equals(RULE))
                .findFirst().orElseThrow();
        assertEquals(1, changed.liveRuleVersion());
        assertEquals(40.0, changed.candidateAlert().triggerValue());
        assertEquals(35.0, changed.liveAlert().triggerValue());
        assertTrue(changed.detail().contains("38.0") && changed.detail().contains("40.0"),
                "CHANGED 差异应说明候选阈值与触发输入值: " + changed.detail());

        ReplayDiff added = result.diffs().stream()
                .filter(d -> d.type() == ReplayDiff.Type.ADDED && d.ruleId().equals("rule-low"))
                .findFirst().orElseThrow();
        assertEquals(20.0, added.candidateAlert().triggerValue());
        assertTrue(added.detail().contains("20.0"), "ADDED 差异应携带触发输入值: " + added.detail());

        // 候选阈值 50 → 线上那次触发整个消失 → REMOVED
        ReplayResult removed = replayService.replay(DEVICE, from, to, List.of(
                new ReplayRuleSpec(RULE, "temperature", AlertRule.Operator.GT, 50.0, true)));
        log.info("[少报场景] diffs={}", removed.diffs());
        assertEquals(1, removed.diffs().size());
        ReplayDiff rem = removed.diffs().get(0);
        assertEquals(ReplayDiff.Type.REMOVED, rem.type());
        assertEquals(1, rem.liveRuleVersion());
        assertEquals(35.0, rem.liveAlert().triggerValue());
    }

    @Test
    @DisplayName("同一批历史 + 同一份候选规则，重复试算结果完全一致")
    void reproducible() {
        buildLiveScenario();
        List<ReplayRuleSpec> candidate = List.of(
                new ReplayRuleSpec(RULE, "temperature", AlertRule.Operator.GT, 38.0, true),
                new ReplayRuleSpec("rule-low", "temperature", AlertRule.Operator.LT, 25.0, true));
        ReplayResult r1 = replayService.replay(DEVICE, 1_000_000L, clock, candidate);
        ReplayResult r2 = replayService.replay(DEVICE, 1_000_000L, clock, candidate);
        ReplayResult r3 = replayService.replay(DEVICE, 1_000_000L, clock, candidate);
        log.info("[可复现] r1==r2==r3: {} / {}", r1.equals(r2), r2.equals(r3));
        assertEquals(r1, r2);
        assertEquals(r2, r3);
    }

    @Test
    @DisplayName("试算只读：不改动线上告警、判定历史与设备影子")
    void replayDoesNotTouchLiveState() {
        buildLiveScenario();
        int alertsBefore = alertStore.allAlerts().size();
        int historyBefore = alertStore.allHistory().size();
        long rulesBefore = alertStore.allRules().size();

        ReplayResult result = replayService.replay(DEVICE, 1_000_000L, clock, List.of(
                new ReplayRuleSpec(RULE, "temperature", AlertRule.Operator.GT, 10.0, true),
                new ReplayRuleSpec("rule-x", "temperature", AlertRule.Operator.LT, 0.0, true)));
        assertTrue(result.candidateAlerts().size() >= 1, "候选阈值 10 应至少触发一次");

        assertEquals(alertsBefore, alertStore.allAlerts().size(), "试算不得新增线上告警");
        assertEquals(historyBefore, alertStore.allHistory().size(), "试算不得新增判定历史");
        assertEquals(rulesBefore, alertStore.allRules().size(), "试算不得写入规则");
        // 线上后续判定照常：场景里告警已恢复，再发越限应触发全新告警（状态机继续推进）
        send(4, 33.0);
        assertEquals(AlertVerdict.TRIGGERED,
                alertStore.allHistory().get(alertStore.allHistory().size() - 1).verdict());
        assertEquals(alertsBefore + 1, alertStore.allAlerts().size());
        log.info("[互不影响] alerts={} history={} 试算后线上判定继续正常", alertsBefore, historyBefore);
    }

    @Test
    @DisplayName("历史被淘汰与确实无数据可区分：PARTIAL_EVICTED / FULL / EMPTY")
    void coverageVisibility() {
        TelemetryHistoryStore tiny = new TelemetryHistoryStore(2, 100);
        DeviceLocks locks = new DeviceLocks();
        AlertStore store2 = new AlertStore();
        AlertService alerts2 = new AlertService(store2, registry, locks);
        TelemetryService telemetry2 = new TelemetryService(registry, new ShadowStore(), locks,
                alerts2, tiny, 5000, 60000);
        ReplayService replay2 = new ReplayService(tiny, store2, registry, locks);
        registry.register(new Device("dev-tiny", "小容量设备",
                Map.of("temperature", new Device.MetricBounds(-40, 85))));

        long base = 2_000_000L;
        for (int i = 1; i <= 4; i++) {
            telemetry2.ingest(new TelemetryReport("dev-tiny", i, base + i * 1000,
                    Map.of("temperature", 20.0), base + i * 1000));
        }
        log.info("[淘汰后] earliest={} size={}", tiny.earliestRetainedTs("dev-tiny"), tiny.sizeOf("dev-tiny"));

        List<ReplayRuleSpec> candidate = List.of(
                new ReplayRuleSpec("r1", "temperature", AlertRule.Operator.GT, 30.0, true));
        // 窗口起点早于最早留存点 → PARTIAL_EVICTED（这段历史已被淘汰，而非“没有告警”）
        ReplayResult partial = replay2.replay("dev-tiny", base, base + 4000, candidate);
        assertEquals(ReplayResult.Coverage.PARTIAL_EVICTED, partial.coverage());
        assertEquals(2, partial.pointsReplayed());
        // 窗口完全在留存范围内且无越限 → FULL 且零告警 = 确实没有告警
        ReplayResult full = replay2.replay("dev-tiny", base + 3000, base + 4000, candidate);
        assertEquals(ReplayResult.Coverage.FULL, full.coverage());
        assertTrue(full.candidateAlerts().isEmpty());
        // 从未上报过的设备 → EMPTY
        ReplayResult empty = replay2.replay(DEVICE, base, base + 4000, candidate);
        assertEquals(ReplayResult.Coverage.EMPTY, empty.coverage());
        log.info("[覆盖区分] partial={} full={} empty={}",
                partial.coverage(), full.coverage(), empty.coverage());
    }
}
