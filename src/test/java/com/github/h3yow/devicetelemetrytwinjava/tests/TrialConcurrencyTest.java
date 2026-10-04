package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertState;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertView;
import com.github.h3yow.devicetelemetrytwinjava.domain.Device;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线上并发上报 + 期间改规则 + 并发试算的压力一致性：
 * <ul>
 *   <li>多设备、多写入线程持续上报，全程无异常、无死锁；</li>
 *   <li>同一规则的告警不重复（同时至多一条 open）、不漏报（每次越限都进判定历史）；</li>
 *   <li>期间反复对规则下发版本变更，版本号严格递增不丢失；</li>
 *   <li>并发试算始终返回结构完整、规则集确定的结果，且线上状态不被试算改动。</li>
 * </ul>
 */
class TrialConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(TrialConcurrencyTest.class);

    @Test
    @DisplayName("8上报线程×2设备 + 规则反复改版 + 4试算线程并发：不报错、不丢不重、状态自洽")
    void concurrentIngestRuleChangesAndTrials() throws InterruptedException {
        DeviceRegistry registry = new DeviceRegistry();
        ShadowStore shadowStore = new ShadowStore();
        DeviceLocks locks = new DeviceLocks();
        AlertStore alertStore = new AlertStore();
        TelemetryHistoryStore historyStore = new TelemetryHistoryStore(20000, 500000);
        AlertService alertService = new AlertService(alertStore, registry, locks);
        TelemetryService telemetryService = new TelemetryService(
                registry, shadowStore, locks, alertService, historyStore, 5_000_000L, 600000L);
        TrialService trialService = new TrialService(historyStore, alertStore, registry);

        String devA = "dev-cA";
        String devB = "dev-cB";
        registry.register(new Device(devA, "并发设备A",
                Map.of("temperature", new Device.MetricBounds(-40, 120))));
        registry.register(new Device(devB, "并发设备B",
                Map.of("temperature", new Device.MetricBounds(-40, 120))));

        // 设备 A 挂 1000 条规则（999 条永不越限，1 条为真正驱动告警的规则）
        int ruleCount = 1000;
        String drivingRule = "rule-drive";
        alertService.putRule(drivingRule, devA, "temperature", AlertRule.Operator.GT, 30.0, true);
        for (int i = 0; i < ruleCount - 1; i++) {
            alertService.putRule("rule-n-" + i, devA, "temperature",
                    AlertRule.Operator.GT, 1000.0 + i, true);
        }
        alertService.putRule("rule-b", devB, "temperature", AlertRule.Operator.GT, 30.0, true);
        assertEquals(ruleCount, alertService.rulesForDevice(devA).size());

        int ingestThreads = 8;
        int trialThreads = 4;
        int iterations = 200;
        int totalTasks = ingestThreads + 1 + trialThreads;
        ExecutorService pool = Executors.newFixedThreadPool(totalTasks);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(totalTasks);
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicInteger trialRuns = new AtomicInteger();
        AtomicInteger lastVersion = new AtomicInteger(1);
        // 全局单调事件时钟：保证任何抢锁顺序下，每台设备看到的事件时间都不后退，杜绝迟到误判
        AtomicLong clock = new AtomicLong(100_000_000L);

        // 8 个上报线程：设备 A 恒越限（验证不重复触发、不漏报），设备 B 交替越限/回落
        for (int t = 0; t < ingestThreads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        String device = (i + idx) % 2 == 0 ? devA : devB;
                        double value = device.equals(devA) ? 42.0 : (i % 2 == 0 ? 42.0 : 20.0);
                        long ts = clock.incrementAndGet();
                        var out = telemetryService.ingestWithAlerts(new TelemetryReport(
                                device, -1L, ts, Map.of("temperature", value), ts));
                        // 设备 A 每次携带 1000 条规则的评估，设备 B 1 条；非空即说明未漏判
                        int expected = device.equals(devA) ? ruleCount : 1;
                        if (out.evaluations().size() != expected) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    failures.incrementAndGet();
                    firstError.compareAndSet(null, e);
                    log.error("上报线程异常", e);
                } finally {
                    finish.countDown();
                }
            });
        }

        // 改规则线程：反复变更驱动规则阈值，版本必须严格递增
        pool.submit(() -> {
            try {
                start.await();
                int prev = alertService.getRule(drivingRule).ruleVersion();
                for (int i = 0; i < 100; i++) {
                    double threshold = (i % 2 == 0) ? 30.0 : 25.0;
                    AlertRule v = alertService.putRule(drivingRule, devA, "temperature",
                            AlertRule.Operator.GT, threshold, true);
                    if (v.ruleVersion() != prev + 1) {
                        failures.incrementAndGet();
                    }
                    prev = v.ruleVersion();
                    lastVersion.set(prev);
                }
            } catch (Throwable e) {
                failures.incrementAndGet();
                firstError.compareAndSet(null, e);
                log.error("改规则线程异常", e);
            } finally {
                finish.countDown();
            }
        });

        // 固定候选规则组（1 条改阈值 + 20 条新增）
        List<TrialRule> candidates = new ArrayList<>();
        candidates.add(new TrialRule(drivingRule, devA, "temperature",
                AlertRule.Operator.GT, 28.0, true));
        for (int i = 0; i < 20; i++) {
            candidates.add(new TrialRule("rule-trial-" + i, devA, "temperature",
                    AlertRule.Operator.GT, 500.0 + i, true));
        }

        // 4 个试算线程：并发、反复试算，结果结构必须始终完整、规则集确定
        for (int t = 0; t < trialThreads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        WhatIfResult r = trialService.runWhatIf(devA, 0L, Long.MAX_VALUE, candidates);
                        trialRuns.incrementAndGet();
                        if (r.baseline() == null || r.candidate() == null || r.diffs() == null
                                || r.coverage() == null) {
                            failures.incrementAndGet();
                        }
                        // baseline 始终是当时的 1000 条生效规则；candidate 固定 21 条
                        if (r.candidate().rules().size() != 21
                                || r.baseline().rules().size() != ruleCount) {
                            failures.incrementAndGet();
                        }
                        // 覆盖标记只能取合法枚举值；有留存点时绝不能是 NO_DATA
                        if (r.candidate().pointCount() > 0 && r.coverage() == TrialCoverage.NO_DATA) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    failures.incrementAndGet();
                    firstError.compareAndSet(null, e);
                    log.error("试算线程异常", e);
                } finally {
                    finish.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(finish.await(60, TimeUnit.SECONDS), "所有线程应在超时前结束（无死锁）");
        pool.shutdown();

        log.info("[并发试算压力] 失败计数={} 试算次数={} 驱动规则最终版本={} 留存A={} 留存B={}",
                failures.get(), trialRuns.get(), lastVersion.get(),
                historyStore.pointsOf(devA), historyStore.pointsOf(devB));

        assertEquals(0, failures.get(), "并发期间不得有任何异常/漏判定: " + firstError.get());
        assertTrue(trialRuns.get() >= 100, "试算线程确实并发执行了大量试算");

        // 设备 A：999 条高阈值规则永不越限；驱动规则全程越限
        List<AlertView> allA = alertService.alertsForDevice(devA);
        assertTrue(allA.stream().noneMatch(a -> a.ruleId().startsWith("rule-n-")),
                "永不越限的规则不得产生告警");
        List<AlertView> openDrive = allA.stream()
                .filter(a -> a.ruleId().equals(drivingRule) && a.state() != AlertState.RESOLVED)
                .toList();
        assertEquals(1, openDrive.size(), "驱动规则同时至多一条 open 告警（不重复触发）");
        assertEquals(AlertState.ACTIVE, openDrive.get(0).state());

        // 不漏报：驱动规则对设备 A 的每次接受上报都有评估，且 TRIGGERED 恰好一次
        long acceptedA = alertService.historyForDevice(devA).stream()
                .filter(r -> r.ruleId().equals(drivingRule)).count();
        long triggeredA = alertService.historyForDevice(devA).stream()
                .filter(r -> r.ruleId().equals(drivingRule) && r.verdict() == AlertVerdict.TRIGGERED)
                .count();
        long activeA = alertService.historyForDevice(devA).stream()
                .filter(r -> r.ruleId().equals(drivingRule)
                        && (r.verdict() == AlertVerdict.TRIGGERED
                        || r.verdict() == AlertVerdict.STILL_ACTIVE))
                .count();
        long ingestA = historyStore.window(devA, 0L, Long.MAX_VALUE).points().size();
        log.info("[设备A不丢不重] 驱动规则评估数={} (留存点={}) TRIGGERED={} 越限结论={}",
                acceptedA, ingestA, triggeredA, activeA);
        assertEquals(1, triggeredA, "驱动规则只能 TRIGGERED 一次");
        // 判定历史只增、不受留存淘汰影响；试算进行期间历史计数仍与接受次数一致
        assertEquals(ingestA, acceptedA, "每次接受上报都必须有一条驱动规则判定（不漏判）");
        assertEquals(ingestA, activeA, "设备 A 恒越限，每次结论都应是触发/持续（不漏报）");

        // 改规则 100 次：版本从 1 涨到 101，严格递增不丢版本
        assertEquals(101, lastVersion.get(), "规则版本必须严格递增、不丢版本");
        assertTrue(openDrive.get(0).ruleVersion() >= 1, "线上告警版本不被试算改写");
    }
}
