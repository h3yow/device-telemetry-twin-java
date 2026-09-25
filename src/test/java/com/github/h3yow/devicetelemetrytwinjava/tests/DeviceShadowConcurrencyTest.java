package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.github.h3yow.devicetelemetrytwinjava.common.IngestionStatus;
import com.github.h3yow.devicetelemetrytwinjava.device.DeviceRegistry;
import com.github.h3yow.devicetelemetrytwinjava.pipeline.IngestionPipeline;
import com.github.h3yow.devicetelemetrytwinjava.shadow.DeviceShadowService;
import com.github.h3yow.devicetelemetrytwinjava.shadow.ShadowState;
import com.github.h3yow.devicetelemetrytwinjava.shadow.ShadowUpdateResult;
import com.github.h3yow.devicetelemetrytwinjava.telemetry.TelemetryMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景二：设备影子版本冲突、非法状态拒绝，以及多入口并发读写一致性。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DeviceShadowConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(DeviceShadowConcurrencyTest.class);
    private static final String DEVICE = "device-shadow";

    @Autowired
    private IngestionPipeline pipeline;
    @Autowired
    private DeviceRegistry registry;
    @Autowired
    private DeviceShadowService shadowService;

    @BeforeEach
    void setUp() {
        registry.register(DEVICE, java.util.Set.of("temp", "target"));
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    @Test
    void optimistic_lock_conflict_is_rejected_and_distinguishable() {
        // 两个客户端都读到初始 desiredVersion=-1
        ShadowState read1 = shadowService.get(DEVICE).orElse(null);
        log.info("[初始读取] {}", read1);

        ShadowUpdateResult first = shadowService.updateDesired(DEVICE, Map.of("target", 40.0), -1);
        assertTrue(first.accepted(), first.reason());
        long versionAfterFirst = first.state().desiredVersion();
        log.info("[第一次下发] accepted=true version={}", versionAfterFirst);

        // 第二次基于过期版本 0（初始版本）提交 -> VERSION_CONFLICT
        ShadowUpdateResult staleSubmit =
                shadowService.updateDesired(DEVICE, Map.of("target", 50.0), 0);
        log.info("[过期版本提交] accepted={} reason={}", staleSubmit.accepted(), staleSubmit.reason());
        assertEquals(false, staleSubmit.accepted());
        assertTrue(staleSubmit.reason().startsWith("VERSION_CONFLICT"));

        // 盲写（expectedVersion=-1，不做版本校验）应成功
        ShadowUpdateResult blindSubmit =
                shadowService.updateDesired(DEVICE, Map.of("target", 60.0), -1);
        assertTrue(blindSubmit.accepted(), blindSubmit.reason());
        long currentVersion = blindSubmit.state().desiredVersion();

        // 基于正确版本提交 -> 成功
        ShadowUpdateResult freshSubmit =
                shadowService.updateDesired(DEVICE, Map.of("target", 50.0), currentVersion);
        assertTrue(freshSubmit.accepted(), freshSubmit.reason());
        assertEquals(50.0, freshSubmit.state().desired().get("target"));
        log.info("[正确版本提交] accepted=true desired={}", freshSubmit.state().desired());
    }

    @Test
    void invalid_desired_state_is_rejected() {
        // 不在白名单的指标
        ShadowUpdateResult badMetric =
                shadowService.updateDesired(DEVICE, Map.of("pressure", 1.0), -1);
        log.info("[非法指标] accepted={} reason={}", badMetric.accepted(), badMetric.reason());
        assertEquals(false, badMetric.accepted());
        assertTrue(badMetric.reason().startsWith("INVALID_STATE"));

        // 非法数值
        ShadowUpdateResult badValue =
                shadowService.updateDesired(DEVICE, Map.of("target", Double.POSITIVE_INFINITY), -1);
        assertEquals(false, badValue.accepted());
        assertTrue(badValue.reason().startsWith("INVALID_STATE"));
    }

    @Test
    void concurrent_telemetry_writes_never_regress_reported_version() throws Exception {
        int threads = 16;
        int perThread = 20;
        int total = threads * perThread;

        // 每个线程领取不相交的 seq 区间，但内部乱序提交：
        // 乱序的旧报文必须被 STALE 拒绝，而最终状态必须等于最大 seq，无重复计数。
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejectedStale = new AtomicInteger();
        AtomicInteger otherRejections = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int base = i * perThread;
            pool.submit(() -> {
                try {
                    start.await();
                    // 逆序提交，强制产生乱序旧报文
                    for (int j = perThread - 1; j >= 0; j--) {
                        long seq = base + j;
                        TelemetryMessage m = new TelemetryMessage(
                                DEVICE, Map.of("temp", (double) seq),
                                1_000_000 + seq, seq, id());
                        log.info("[并发输入] thread={} seq={}", Thread.currentThread().getId(), seq);
                        var out = pipeline.ingest(m);
                        if (out.ingestion().appliedToShadow()) {
                            accepted.incrementAndGet();
                        } else if (out.ingestion().status() == IngestionStatus.REJECTED_STALE) {
                            rejectedStale.incrementAndGet();
                        } else {
                            otherRejections.incrementAndGet();
                            log.warn("[非预期拒绝] seq={} status={} 依据={}",
                                    seq, out.ingestion().status(), out.ingestion().reason());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        ShadowState finalState = shadowService.get(DEVICE).orElseThrow();
        log.info("[并发结束] accepted={} rejectedStale={} otherRejections={} reportedVersion={} reported={}",
                accepted.get(), rejectedStale.get(), otherRejections.get(),
                finalState.reportedVersion(), finalState.reported());

        // 只有「被乱序旧报文顶掉」的拒绝，且无任何其他拒绝
        assertEquals(0, otherRejections.get());
        // 接受数 + 过期拒绝数 = 总投递数（每条都得到明确结论，无静默丢弃）
        assertEquals(total, accepted.get() + rejectedStale.get());
        // 最大 seq 必须胜出，影子不被旧数据回退
        assertEquals(total - 1L, finalState.reportedVersion());
        assertEquals((double) total - 1, finalState.reported().get("temp"));
    }
}
