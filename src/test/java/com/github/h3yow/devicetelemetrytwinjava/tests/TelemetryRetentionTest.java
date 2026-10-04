package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.Device;
import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryHistoryWindow;
import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryReport;
import com.github.h3yow.devicetelemetrytwinjava.domain.TrialCoverage;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 有界历史留存与淘汰语义：
 * 容量上限确定生效（按 FIFO 淘汰最旧），且查询/试算能区分
 * COMPLETE（完整）、PARTIAL_EVICTED（被淘汰）、NO_DATA（确实没有数据/告警）。
 */
class TelemetryRetentionTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryRetentionTest.class);
    private static final String DEVICE = "dev-retain";

    private DeviceRegistry registry;
    private TelemetryService telemetryService;
    private TelemetryHistoryStore historyStore;
    private TrialService trialService;
    private long clock;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        ShadowStore shadowStore = new ShadowStore();
        DeviceLocks locks = new DeviceLocks();
        AlertStore alertStore = new AlertStore();
        // 每设备仅留 5 条，便于触发淘汰
        historyStore = new TelemetryHistoryStore(5, 1000);
        AlertService alertService = new AlertService(alertStore, registry, locks);
        telemetryService = new TelemetryService(registry, shadowStore, locks, alertService,
                historyStore, 5000, 60000);
        trialService = new TrialService(historyStore, alertStore, registry);
        registry.register(new Device(DEVICE, "留存设备",
                Map.of("temperature", new Device.MetricBounds(-40, 85))));
        alertService.putRule("r1", DEVICE, "temperature", AlertRule.Operator.GT, 30.0, true);
        clock = 20_000_000L;
    }

    private long send(double temp) {
        clock += 1000;
        telemetryService.ingestWithAlerts(new TelemetryReport(
                DEVICE, -1L, clock, Map.of("temperature", temp), clock));
        return clock;
    }

    @Test
    @DisplayName("超容量后按 FIFO 淘汰最旧数据，留存点数与总量有明确上限")
    void evictsOldestWhenOverCapacity() {
        for (int i = 0; i < 12; i++) {
            send(20.0);
        }
        assertEquals(5, historyStore.pointsOf(DEVICE), "每设备最多留存 5 条");
        assertEquals(5, historyStore.totalPoints());
        // 只覆盖“仍留存区间”的窗口：完整、无数据落在淘汰区
        TelemetryHistoryWindow w = historyStore.window(DEVICE, 20_008_000L, 20_012_000L);
        assertEquals(5, w.points().size());
        // 最早的点已被淘汰：留存最早事件时间应为第 8 个点（ts = 20_000_000 + 8000）
        assertEquals(20_008_000L, w.oldestTs());
        assertEquals(20_012_000L, w.latestTs());
        assertEquals(TrialCoverage.COMPLETE, w.coverage(),
                "窗口完全落在留存区间内 -> COMPLETE");
        assertNotNull(w.evictedHighTs());
        // 而查询包含淘汰区的全范围窗口应能识别出部分被淘汰
        TelemetryHistoryWindow full = historyStore.window(DEVICE, 0L, Long.MAX_VALUE);
        assertEquals(TrialCoverage.PARTIAL_EVICTED, full.coverage());
        log.info("[淘汰] 留存5条 oldest={} latest={} 淘汰高水位={}",
                w.oldestTs(), w.latestTs(), w.evictedHighTs());
    }

    @Test
    @DisplayName("窗口落在已淘汰区间 -> PARTIAL_EVICTED，不能误报为“无数据/无告警”")
    void partialEvictedDistinguishedFromNoData() {
        long first = send(50.0); // 越限，但很快会被淘汰
        long second = send(50.0);
        long third = send(50.0);
        for (int i = 0; i < 5; i++) {
            send(20.0); // 把前 3 条越限数据挤出留存
        }
        assertEquals(5, historyStore.pointsOf(DEVICE));

        // 请求包含被淘汰越限数据的窗口
        WhatIfResult r = trialService.runWhatIf(DEVICE, first, clock,
                List.of(new TrialRule("r1", DEVICE, "temperature",
                        AlertRule.Operator.GT, 30.0, true)));
        assertEquals(TrialCoverage.PARTIAL_EVICTED, r.coverage(),
                "窗口起点早于仍保留的最早数据 -> 必须标记为部分被淘汰");
        assertTrue(r.evictedHighTs() >= first, "淘汰高水位应覆盖最早的越限数据");
        log.info("[部分淘汰] coverage={} evictedHighTs={} oldest={} 候选段数={}",
                r.coverage(), r.evictedHighTs(), r.oldestTs(), r.candidate().episodes().size());
    }

    @Test
    @DisplayName("留存完整但窗口内确实没有数据 -> NO_DATA；完整且无告警 -> COMPLETE")
    void noDataVsComplete() {
        long a = send(20.0);
        long b = send(20.0);

        // 完整窗口、有数据、但全程不越限：COMPLETE 且零告警段
        WhatIfResult complete = trialService.runWhatIf(DEVICE, a, b,
                List.of(new TrialRule("r1", DEVICE, "temperature",
                        AlertRule.Operator.GT, 30.0, true)));
        assertEquals(TrialCoverage.COMPLETE, complete.coverage());
        assertEquals(0, complete.baseline().episodes().size(), "完整窗口内确实没有告警");
        assertEquals(0, complete.diffs().size());

        // 请求一段从未有过数据、也不与淘汰区相交的未来窗口：NO_DATA
        long futureFrom = clock + 1_000_000L;
        WhatIfResult empty = trialService.runWhatIf(DEVICE, futureFrom, futureFrom + 1000,
                List.of(new TrialRule("r1", DEVICE, "temperature",
                        AlertRule.Operator.GT, 30.0, true)));
        assertEquals(TrialCoverage.NO_DATA, empty.coverage(),
                "留存完整但窗口确实无数据 -> NO_DATA，不能与被淘汰混淆");
        log.info("[覆盖区分] 完整无告警={} 无数据窗口={}", complete.coverage(), empty.coverage());
    }

    @Test
    @DisplayName("被拒绝/重复/迟到的上报不进入留存")
    void onlyAcceptedPointsRetained() {
        long t = send(20.0);
        // 重传与首条完全相同（首条 seq 由平台分配为 0）：DUPLICATE，不留存
        telemetryService.ingestWithAlerts(new TelemetryReport(
                DEVICE, 0L, t, Map.of("temperature", 20.0), t));
        // 全越界拒绝：不留存
        telemetryService.ingestWithAlerts(new TelemetryReport(
                DEVICE, -1L, t + 1000, Map.of("temperature", 999.0), t + 1000));
        // 只有 1 条合法点
        TelemetryHistoryWindow w = historyStore.window(DEVICE, 0, Long.MAX_VALUE);
        assertEquals(1, w.points().size(), "仅被接受的遥测进入留存");
        log.info("[留存口径] 合法点={}（重复/越界拒绝均不入留存）", w.points().size());
    }
}
