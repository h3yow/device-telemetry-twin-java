package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmEvent;
import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmEventType;
import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmService;
import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmState;
import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmStatus;
import com.github.h3yow.devicetelemetrytwinjava.common.IngestionStatus;
import com.github.h3yow.devicetelemetrytwinjava.device.DeviceRegistry;
import com.github.h3yow.devicetelemetrytwinjava.pipeline.IngestionOutcome;
import com.github.h3yow.devicetelemetrytwinjava.pipeline.IngestionPipeline;
import com.github.h3yow.devicetelemetrytwinjava.rule.ComparisonOp;
import com.github.h3yow.devicetelemetrytwinjava.rule.RuleService;
import com.github.h3yow.devicetelemetrytwinjava.telemetry.TelemetryMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景三：告警触发 / 抑制 / 恢复在同一份状态上自洽；并发上报不产生重复告警。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AlarmLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(AlarmLifecycleTest.class);
    private static final String DEVICE = "device-alarm";
    private static final String RULE = "temp-high";

    @Autowired
    private IngestionPipeline pipeline;
    @Autowired
    private DeviceRegistry registry;
    @Autowired
    private RuleService ruleService;
    @Autowired
    private AlarmService alarmService;

    @BeforeEach
    void setUp() {
        registry.register(DEVICE, java.util.Set.of("temp"));
        ruleService.current(RULE).orElseGet(() ->
                ruleService.register(RULE, DEVICE, "temp", ComparisonOp.GT, 90, 2_000));
    }

    private IngestionOutcome send(long seq, long ts, double temp) {
        TelemetryMessage m = new TelemetryMessage(
                DEVICE, Map.of("temp", temp), ts, seq, UUID.randomUUID().toString());
        log.info("[测试输入] seq={} eventTime={} temp={}", seq, ts, temp);
        IngestionOutcome o = pipeline.ingest(m);
        for (AlarmEvent e : o.alarmEvents()) {
            log.info("[告警判定] {} rev={} 依据={}", e.type(), e.revision(), e.basis());
        }
        return o;
    }

    @Test
    void fire_suppress_recover_lifecycle_is_self_consistent() {
        long t = 5_000_000;

        // 正常区间：无事件
        assertTrue(send(1, t, 80).alarmEvents().isEmpty());

        // 越限 -> FIRE
        IngestionOutcome fire = send(2, t + 1_000, 95);
        assertEquals(1, fire.alarmEvents().size());
        assertEquals(AlarmEventType.FIRE, fire.alarmEvents().get(0).type());
        assertEquals(AlarmStatus.ACTIVE,
                alarmService.currentState(DEVICE, RULE).orElseThrow().status());

        // 抑制窗口内继续越限 -> SUPPRESSED，不产生新的 FIRE
        IngestionOutcome suppressed = send(3, t + 2_000, 96);
        assertEquals(1, suppressed.alarmEvents().size());
        assertEquals(AlarmEventType.SUPPRESSED, suppressed.alarmEvents().get(0).type());

        // 仍越限但超过抑制窗口 -> 再次 FIRE（周期性提醒）
        IngestionOutcome refire = send(4, t + 4_500, 97);
        assertEquals(AlarmEventType.FIRE, refire.alarmEvents().get(0).type());

        // 回落 -> RECOVER
        IngestionOutcome recover = send(5, t + 5_000, 80);
        assertEquals(AlarmEventType.RECOVER, recover.alarmEvents().get(0).type());
        assertEquals(AlarmStatus.RECOVERED,
                alarmService.currentState(DEVICE, RULE).orElseThrow().status());

        // 恢复后正常区间不产生事件；再次越限应是全新 FIRE 而非 SUPPRESSED
        assertTrue(send(6, t + 6_000, 81).alarmEvents().isEmpty());
        IngestionOutcome fireAgain = send(7, t + 7_000, 95);
        assertEquals(AlarmEventType.FIRE, fireAgain.alarmEvents().get(0).type());

        List<AlarmEvent> history = alarmService.replay(DEVICE, RULE);
        log.info("[事件序列] {}", history.stream()
                .map(e -> e.type() + "@seq" + e.seq())
                .collect(Collectors.toList()));
        assertEquals(List.of(
                AlarmEventType.FIRE,
                AlarmEventType.SUPPRESSED,
                AlarmEventType.FIRE,
                AlarmEventType.RECOVER,
                AlarmEventType.FIRE),
                history.stream().map(AlarmEvent::type).toList());
    }

    @Test
    void duplicate_or_stale_telemetry_never_creates_alarm() {
        long t = 6_000_000;
        String mid = UUID.randomUUID().toString();
        TelemetryMessage breach = new TelemetryMessage(DEVICE, Map.of("temp", 99.0), t, 1, mid);

        IngestionOutcome first = pipeline.ingest(breach);
        assertEquals(IngestionStatus.ACCEPTED, first.ingestion().status());
        assertEquals(1, first.alarmEvents().size());

        // 完全相同的重发 -> 重复拒绝，无任何新告警
        IngestionOutcome dup = pipeline.ingest(breach);
        assertEquals(IngestionStatus.REJECTED_DUPLICATE, dup.ingestion().status());
        assertTrue(dup.alarmEvents().isEmpty());

        // 伪造更旧 seq -> 过期拒绝，无告警
        IngestionOutcome stale = pipeline.ingest(new TelemetryMessage(
                DEVICE, Map.of("temp", 120.0), t - 1_000, 0, UUID.randomUUID().toString()));
        assertEquals(IngestionStatus.REJECTED_STALE, stale.ingestion().status());
        assertTrue(stale.alarmEvents().isEmpty());

        assertEquals(1, alarmService.replay(DEVICE, RULE).size());
    }

    @Test
    void concurrent_breaching_reports_produce_exactly_one_fire() throws Exception {
        String device = "device-alarm-concurrent";
        registry.register(device, java.util.Set.of("temp"));
        String rule = "temp-high-c";
        ruleService.register(rule, device, "temp", ComparisonOp.GT, 90, 0);

        int n = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        java.util.concurrent.atomic.AtomicInteger applied = new java.util.concurrent.atomic.AtomicInteger();
        // suppressMillis=0 且所有并发报文事件时间相同（同一时刻并发越限）：
        // 乱序的旧 seq 会被 STALE 拒绝（不产生告警）；所有成功接入的越限报文中，
        // 只有第一条 FIRE，其余全部 SUPPRESSED，绝不出现重复 FIRE。
        long sameTime = 7_000_000;
        for (int i = 0; i < n; i++) {
            final long seq = i + 1;
            pool.submit(() -> {
                try {
                    start.await();
                    var out = pipeline.ingest(new TelemetryMessage(device, Map.of("temp", 95.0),
                            sameTime, seq, UUID.randomUUID().toString()));
                    if (out.ingestion().appliedToShadow()) {
                        applied.incrementAndGet();
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

        long fires = alarmService.replay(device, rule).stream()
                .filter(e -> e.type() == AlarmEventType.FIRE).count();
        long suppressed = alarmService.replay(device, rule).stream()
                .filter(e -> e.type() == AlarmEventType.SUPPRESSED).count();
        log.info("[并发越限] 成功接入={} FIRE 数量={}（期望 1），SUPPRESSED={}（期望 {}）",
                applied.get(), fires, suppressed, applied.get() - 1);
        assertEquals(1, fires);
        assertEquals(applied.get() - 1, suppressed);

        AlarmState state = alarmService.currentState(device, rule).orElseThrow();
        assertEquals(AlarmStatus.ACTIVE, state.status());
        assertEquals(n, state.lastEvaluatedSeq());
    }
}
