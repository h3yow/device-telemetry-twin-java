package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict;
import com.github.h3yow.devicetelemetrytwinjava.domain.Device;
import com.github.h3yow.devicetelemetrytwinjava.domain.IngestStatus;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试算与线上并发：多设备并发上报 + 期间修改规则 + 反复试算，
 * 线上不丢判定、不产生重复告警，试算不报错且结果在输入稳定后可复现。
 */
class ReplayConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayConcurrencyTest.class);

    @Test
    @DisplayName("并发上报+改规则+反复试算：线上不丢不重，试算无异常，输入稳定后试算可复现")
    void replayWhileIngestingAndChangingRules() throws InterruptedException {
        DeviceRegistry registry = new DeviceRegistry();
        ShadowStore shadowStore = new ShadowStore();
        DeviceLocks locks = new DeviceLocks();
        AlertStore alertStore = new AlertStore();
        AlertService alertService = new AlertService(alertStore, registry, locks);
        TelemetryHistoryStore historyStore = new TelemetryHistoryStore(50000, 500000);
        TelemetryService telemetryService = new TelemetryService(
                registry, shadowStore, locks, alertService, historyStore, 5000, 60000);
        ReplayService replayService = new ReplayService(historyStore, alertStore, registry, locks);

        int devices = 4;
        int reportsPerDevice = 50;
        for (int d = 0; d < devices; d++) {
            String device = "dev-conc-" + d;
            registry.register(new Device(device, "并发试算设备" + d,
                    Map.of("temperature", new Device.MetricBounds(-40, 85))));
            alertService.putRule("rule-c-" + d, device, "temperature",
                    AlertRule.Operator.GT, 30.0, true);
        }

        ExecutorService pool = Executors.newFixedThreadPool(devices + 2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(devices + 2);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger device0Accepted = new AtomicInteger();
        AtomicInteger replayRuns = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        // 上报线程：每台设备一个线程，交替发送越限/正常值
        for (int d = 0; d < devices; d++) {
            String device = "dev-conc-" + d;
            boolean isDevice0 = d == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < reportsPerDevice; i++) {
                        double temp = i % 2 == 0 ? 42.0 : 20.0;
                        long now = System.currentTimeMillis();
                        var out = telemetryService.ingestWithAlerts(new TelemetryReport(
                                device, -1L, now, Map.of("temperature", temp), now));
                        if (out.result().status() == IngestStatus.ACCEPTED) {
                            accepted.incrementAndGet();
                            if (isDevice0) {
                                device0Accepted.incrementAndGet();
                            }
                        }
                        if (i % 5 == 0) {
                            Thread.sleep(1); // 放慢上报，让试算/改规则与写入充分交错
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        // 改规则线程：期间反复变更阈值（产生新版本），模拟“试算期间有人改规则”；
        // 阈值只在 [30, 32] 区间微调，保证 42 始终越限、20 始终恢复，判定结果与版本无关
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 20; i++) {
                    for (int d = 0; d < devices; d++) {
                        alertService.putRule("rule-c-" + d, "dev-conc-" + d, "temperature",
                                AlertRule.Operator.GT, 30.0 + i * 0.1, true);
                    }
                }
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        });
        // 试算线程：全程反复对设备 0 试算候选规则
        pool.submit(() -> {
            try {
                start.await();
                while (device0Accepted.get() < reportsPerDevice) { // 设备 0 报满即停
                    ReplayResult r = replayService.replay("dev-conc-0", 0L, Long.MAX_VALUE,
                            List.of(new ReplayRuleSpec("rule-c-0", "temperature",
                                    AlertRule.Operator.GT, 35.0, true)));
                    replayRuns.incrementAndGet();
                    if (r.pointsReplayed() < 0) {
                        errors.add(new IllegalStateException("非法回放点数"));
                    }
                }
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务应在限时内完成");
        pool.shutdown();

        log.info("[并发试算] accepted={}/{} replayRuns={} errors={}",
                accepted.get(), devices * reportsPerDevice, replayRuns.get(), errors);
        assertTrue(errors.isEmpty(), "并发期间不应出现任何异常: " + errors);
        assertEquals(devices * reportsPerDevice, accepted.get(), "全部上报都应被接受（不丢）");
        assertTrue(replayRuns.get() > 0, "试算应实际执行过");

        // 线上判定自洽：每台设备交替越限/正常 → 每次越限都是新触发，无重复 open 告警
        for (int d = 0; d < devices; d++) {
            String device = "dev-conc-" + d;
            long triggered = alertService.historyForDevice(device).stream()
                    .filter(r -> r.verdict() == AlertVerdict.TRIGGERED).count();
            long alerts = alertService.alertsForDevice(device).size();
            log.info("[设备{}] TRIGGERED={} 告警实体数={}", d, triggered, alerts);
            assertEquals(reportsPerDevice / 2, triggered, "每次越限恰好触发一次（不丢不重）");
            assertEquals(triggered, alerts, "告警实体数与触发次数一致（无重复告警）");
        }

        // 输入稳定后：同一窗口 + 同一候选规则，两次试算结果完全一致
        List<ReplayRuleSpec> candidate = List.of(
                new ReplayRuleSpec("rule-c-0", "temperature", AlertRule.Operator.GT, 35.0, true));
        ReplayResult r1 = replayService.replay("dev-conc-0", 0L, Long.MAX_VALUE, candidate);
        ReplayResult r2 = replayService.replay("dev-conc-0", 0L, Long.MAX_VALUE, candidate);
        log.info("[稳定后复现] points={} candidateAlerts={} 两次结果一致={}",
                r1.pointsReplayed(), r1.candidateAlerts().size(), r1.equals(r2));
        assertEquals(r1, r2);
        assertEquals(reportsPerDevice, r1.pointsReplayed());
        assertEquals(reportsPerDevice / 2, r1.candidateAlerts().size(),
                "候选阈值 35 下每次 42 都触发、每次 20 都恢复");
    }
}
