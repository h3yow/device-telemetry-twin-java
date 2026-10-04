package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.github.h3yow.devicetelemetrytwinjava.domain.Device;
import com.github.h3yow.devicetelemetrytwinjava.domain.DeviceShadow;
import com.github.h3yow.devicetelemetrytwinjava.domain.ShadowUpdateResult;
import com.github.h3yow.devicetelemetrytwinjava.domain.ShadowUpdateStatus;
import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryReport;
import com.github.h3yow.devicetelemetrytwinjava.service.AlertService;
import com.github.h3yow.devicetelemetrytwinjava.service.ShadowService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备影子：desired 乐观版本冲突、非法状态拒绝、多入口并发读写一致性。
 */
class ShadowConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ShadowConsistencyTest.class);
    private static final String DEVICE = "dev-shadow";

    private DeviceRegistry registry;
    private ShadowStore shadowStore;
    private ShadowService shadowService;
    private TelemetryService telemetryService;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        shadowStore = new ShadowStore();
        DeviceLocks locks = new DeviceLocks();
        AlertStore alertStore = new AlertStore();
        AlertService alertService = new AlertService(alertStore, registry, locks);
        shadowService = new ShadowService(shadowStore, registry, locks);
        telemetryService = new TelemetryService(registry, shadowStore, locks, alertService, new TelemetryHistoryStore(20000, 200000), 5000, 60000);
        registry.register(new Device(DEVICE, "影子测试设备", Map.of()));
    }

    @Test
    @DisplayName("desired 写入成功 -> 版本冲突可区分 -> 非法状态与未知设备被拒绝")
    void desiredVersionConflictAndValidation() {
        var first = shadowService.writeDesired(DEVICE, Map.of("targetTemp", 22.0), -1);
        assertEquals(ShadowUpdateStatus.OK, first.status(), first.reason());
        assertEquals(1, first.desiredVersion());
        log.info("[首次写入] {}", first);

        // 携带过期版本 0 写入 -> 冲突，且返回当前版本便于重试
        var conflict = shadowService.writeDesired(DEVICE, Map.of("targetTemp", 23.0), 0);
        assertEquals(ShadowUpdateStatus.VERSION_CONFLICT, conflict.status());
        assertEquals(1, conflict.desiredVersion());
        log.info("[版本冲突] {}", conflict);

        // 携带正确版本 1 -> 成功
        var second = shadowService.writeDesired(DEVICE, Map.of("targetTemp", 23.0), 1);
        assertEquals(ShadowUpdateStatus.OK, second.status());
        assertEquals(2, second.desiredVersion());
        log.info("[重试成功] {}", second);

        var empty = shadowService.writeDesired(DEVICE, Map.of(), 2);
        assertEquals(ShadowUpdateStatus.INVALID_STATE, empty.status());

        var badValue = shadowService.writeDesired(DEVICE, Map.of("x", Double.POSITIVE_INFINITY), 2);
        assertEquals(ShadowUpdateStatus.INVALID_STATE, badValue.status());
        log.info("[非法状态] empty={} badValue={}", empty.reason(), badValue.reason());

        var unknown = shadowService.writeDesired("nope", Map.of("x", 1.0), -1);
        assertEquals(ShadowUpdateStatus.UNKNOWN_DEVICE, unknown.status());
        assertNull(shadowService.getShadow("nope"));
        log.info("[未知设备] {}", unknown.reason());

        DeviceShadow finalShadow = shadowService.getShadow(DEVICE);
        assertEquals(2, finalShadow.desiredVersion());
        assertEquals(23.0, finalShadow.desired().get("targetTemp"));
    }

    @Test
    @DisplayName("20 个线程以相同 expectedVersion 并发写 desired：恰好 1 个成功，其余全部 VERSION_CONFLICT")
    void concurrentDesiredWrites() throws InterruptedException {
        // 先建立版本 1
        assertEquals(ShadowUpdateStatus.OK,
                shadowService.writeDesired(DEVICE, Map.of("v", 0.0), -1).status());

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Throwable> errors = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final double value = i;
            pool.submit(() -> {
                try {
                    start.await();
                    var r = shadowService.writeDesired(DEVICE, Map.of("v", value), 1);
                    if (r.status() == ShadowUpdateStatus.OK) {
                        ok.incrementAndGet();
                    } else if (r.status() == ShadowUpdateStatus.VERSION_CONFLICT) {
                        conflict.incrementAndGet();
                    }
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[并发 desired 结果] OK={} VERSION_CONFLICT={} 总线程={}", ok.get(), conflict.get(), threads);
        assertTrue(errors.isEmpty(), "并发过程不应抛异常: " + errors);
        assertEquals(1, ok.get(), "乐观锁下只能有一个写入成功");
        assertEquals(threads - 1, conflict.get(), "其余必须明确报告版本冲突");
        assertEquals(2, shadowService.getShadow(DEVICE).desiredVersion());
    }

    @Test
    @DisplayName("reported 遥测与 desired 写入并发交错：每个快照的版本/内容始终自洽")
    void concurrentReportedAndDesiredSnapshotConsistency() throws InterruptedException {
        int writers = 8;
        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new ArrayList<>();

        // 遥测线程：seq=-1 由平台在临界区内分配，reportedVersion 应恰好增长 4*iterations 次
        for (int w = 0; w < 4; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        long now = System.currentTimeMillis();
                        var out = telemetryService.ingestWithAlerts(new TelemetryReport(
                                DEVICE, -1L, now, Map.of("temp", (double) (i % 40)), now));
                        assertNotNull(out.result());
                    }
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                }
            });
        }
        // desired 写入线程：读快照→CAS 写，冲突即重读重试（标准乐观锁用法），最终全部成功
        for (int w = 0; w < 4; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        ShadowUpdateResult r;
                        int guard = 0;
                        do {
                            DeviceShadow snap = shadowService.getShadow(DEVICE);
                            r = shadowService.writeDesired(DEVICE,
                                    Map.of("setpoint", (double) i), snap.desiredVersion());
                        } while (r.status() == ShadowUpdateStatus.VERSION_CONFLICT && guard++ < 1000);
                        if (r.status() != ShadowUpdateStatus.OK) {
                            throw new AssertionError("CAS 重试后仍失败: " + r);
                        }
                    }
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "并发过程不应抛异常: " + errors);

        DeviceShadow finalShadow = shadowService.getShadow(DEVICE);
        assertEquals(4L * iterations, finalShadow.reportedVersion(),
                "每个被接受遥测恰好 +1，无丢失/重复覆盖");
        assertEquals(4L * iterations, finalShadow.desiredVersion(),
                "读后写在串行化下全部成功");
        // 服务端序号从 0 起连续分配 800 条，最后一条 lastSeq=799
        assertEquals(4L * iterations - 1, finalShadow.lastSeq());
        log.info("[并发交错最终影子] {}", finalShadow);
    }
}
