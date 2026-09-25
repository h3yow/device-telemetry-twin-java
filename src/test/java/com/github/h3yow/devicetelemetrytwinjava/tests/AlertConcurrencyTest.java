package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertState;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertVerdict;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertView;
import com.github.h3yow.devicetelemetrytwinjava.domain.Device;
import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryReport;
import com.github.h3yow.devicetelemetrytwinjava.service.AlertService;
import com.github.h3yow.devicetelemetrytwinjava.service.TelemetryService;
import com.github.h3yow.devicetelemetrytwinjava.store.AlertStore;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceLocks;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceRegistry;
import com.github.h3yow.devicetelemetrytwinjava.store.ShadowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多线程并发遥测上报：告警恰好触发一次（无重复告警），最终状态与历史判定自洽（不漏报）。
 */
class AlertConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(AlertConcurrencyTest.class);

    @Test
    @DisplayName("16 线程并发上报越限值：仅 1 条 TRIGGERED，其余 STILL_ACTIVE，告警实体仅 1 条")
    void concurrentViolationsCreateSingleAlert() throws InterruptedException {
        DeviceRegistry registry = new DeviceRegistry();
        ShadowStore shadowStore = new ShadowStore();
        DeviceLocks locks = new DeviceLocks();
        AlertStore alertStore = new AlertStore();
        AlertService alertService = new AlertService(alertStore, registry, locks);
        TelemetryService telemetryService = new TelemetryService(
                registry, shadowStore, locks, alertService, 5000, 60000);
        String device = "dev-conc-alert";
        registry.register(new Device(device, "并发告警设备",
                Map.of("temperature", new Device.MetricBounds(-40, 85))));
        alertService.putRule("rule-c", device, "temperature", AlertRule.Operator.GT, 30.0, true);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger triggered = new AtomicInteger();
        AtomicInteger stillActive = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long now = System.currentTimeMillis();
                    // seq=-1：平台在锁内分配单调序号，保证所有上报都被视为合法新事件
                    var out = telemetryService.ingestWithAlerts(new TelemetryReport(
                            device, -1L, now, Map.of("temperature", 42.0), now));
                    accepted.incrementAndGet();
                    AlertVerdict v = out.evaluations().get(0).verdict();
                    if (v == AlertVerdict.TRIGGERED) {
                        triggered.incrementAndGet();
                    } else if (v == AlertVerdict.STILL_ACTIVE) {
                        stillActive.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();

        List<AlertView> alerts = alertService.alertsForDevice(device);
        log.info("[并发越限结果] accepted={} TRIGGERED={} STILL_ACTIVE={} 告警实体数={}",
                accepted.get(), triggered.get(), stillActive.get(), alerts.size());

        assertEquals(threads, accepted.get());
        assertEquals(1, triggered.get(), "并发上报只能产生一次 TRIGGERED");
        assertEquals(threads - 1, stillActive.get(), "其余必须是 STILL_ACTIVE（幂等不重复告警）");
        assertEquals(1, alerts.size(), "同一规则同时只能存在一条 open 告警");
        assertEquals(AlertState.ACTIVE, alerts.get(0).state());
        assertEquals(threads, alerts.get(0).triggerCount(), "触发计数应覆盖全部并发上报，证明无漏报");

        // 历史记录同样自洽：1 次 TRIGGERED + n-1 次 STILL_ACTIVE
        long triggeredInHistory = alertService.historyForDevice(device).stream()
                .filter(r -> r.verdict() == AlertVerdict.TRIGGERED).count();
        long stillInHistory = alertService.historyForDevice(device).stream()
                .filter(r -> r.verdict() == AlertVerdict.STILL_ACTIVE).count();
        assertEquals(1, triggeredInHistory);
        assertEquals(threads - 1, stillInHistory);
    }
}
