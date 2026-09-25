package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.github.h3yow.devicetelemetrytwinjava.common.IngestionStatus;
import com.github.h3yow.devicetelemetrytwinjava.device.DeviceRegistry;
import com.github.h3yow.devicetelemetrytwinjava.pipeline.IngestionOutcome;
import com.github.h3yow.devicetelemetrytwinjava.pipeline.IngestionPipeline;
import com.github.h3yow.devicetelemetrytwinjava.shadow.DeviceShadowService;
import com.github.h3yow.devicetelemetrytwinjava.shadow.ShadowState;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景一：同一设备的乱序、重复、跳号与迟到上报识别。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TelemetryOrderingTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryOrderingTest.class);
    private static final String DEVICE = "device-ordering";

    @Autowired
    private IngestionPipeline pipeline;
    @Autowired
    private DeviceRegistry registry;
    @Autowired
    private DeviceShadowService shadowService;

    @BeforeEach
    void setUp() {
        registry.register(DEVICE, java.util.Set.of("temp"));
    }

    private IngestionOutcome send(long seq, long ts, double temp, String messageId) {
        TelemetryMessage m = new TelemetryMessage(DEVICE, Map.of("temp", temp), ts, seq, messageId);
        log.info("[测试输入] seq={} eventTime={} temp={} messageId={}", seq, ts, temp, messageId);
        IngestionOutcome o = pipeline.ingest(m);
        log.info("[测试结论] status={} applied={} 依据={}",
                o.ingestion().status(), o.ingestion().appliedToShadow(), o.ingestion().reason());
        return o;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    @Test
    void outOfOrder_duplicate_gap_and_late_are_distinguished() {
        long t = 1_000_000;

        // seq=1 正常
        assertEquals(IngestionStatus.ACCEPTED, send(1, t, 70, id()).ingestion().status());
        // seq=3 跳号到达 -> 接受但标记 GAP
        IngestionOutcome gap = send(3, t + 2_000, 72, id());
        assertEquals(IngestionStatus.ACCEPTED_GAP, gap.ingestion().status());
        assertTrue(gap.ingestion().appliedToShadow());

        // seq=2 迟到一步（乱序）且 seq 更旧 -> STALE 拒绝，不覆盖
        IngestionOutcome stale = send(2, t + 1_000, 99, id());
        assertEquals(IngestionStatus.REJECTED_STALE, stale.ingestion().status());
        assertFalse(stale.ingestion().appliedToShadow());

        // seq=3 重发：同 messageId 同负载 -> DUPLICATE
        String dupId = UUID.randomUUID().toString();
        send(3, t + 2_000, 72, dupId);
        IngestionOutcome duplicate = send(3, t + 2_000, 72, dupId);
        assertEquals(IngestionStatus.REJECTED_DUPLICATE, duplicate.ingestion().status());

        // seq=4 同 seq 不同负载（新 messageId） -> CONFLICT
        IngestionOutcome conflict = send(3, t + 2_000, 100, id());
        assertEquals(IngestionStatus.REJECTED_CONFLICT, conflict.ingestion().status());

        // seq=4 但事件时间远早于水位（已接受 t+2000，窗口 3000） -> LATE 降级
        IngestionOutcome late = send(4, t - 50_000, 120, id());
        assertEquals(IngestionStatus.ACCEPTED_LATE, late.ingestion().status());
        assertTrue(late.ingestion().status().accepted());
        assertFalse(late.ingestion().appliedToShadow());

        // 最终影子必须停留在 seq=3 / temp=72：过期、重复、迟到都不得覆盖
        ShadowState state = shadowService.get(DEVICE).orElseThrow();
        log.info("[影子快照] version={} reportedVersion={} reported={}",
                state.version(), state.reportedVersion(), state.reported());
        assertEquals(3, state.reportedVersion());
        assertEquals(72.0, state.reported().get("temp"));
    }

    @Test
    void invalid_and_unknown_device_are_rejected_with_distinct_reasons() {
        long t = 2_000_000;
        // 未知设备
        IngestionOutcome unknown = pipeline.ingest(
                new TelemetryMessage("no-such-device", Map.of("temp", 1.0), t, 1, id()));
        log.info("[未知设备结论] {} : {}", unknown.ingestion().status(), unknown.ingestion().reason());
        assertEquals(IngestionStatus.REJECTED_UNKNOWN_DEVICE, unknown.ingestion().status());

        // 非法指标（不在白名单）
        IngestionOutcome invalidMetric = pipeline.ingest(
                new TelemetryMessage(DEVICE, Map.of("humidity", 1.0), t, 1, id()));
        log.info("[非法指标结论] {} : {}", invalidMetric.ingestion().status(), invalidMetric.ingestion().reason());
        assertEquals(IngestionStatus.REJECTED_INVALID, invalidMetric.ingestion().status());

        // 非法值（NaN）
        IngestionOutcome nan = pipeline.ingest(
                new TelemetryMessage(DEVICE, Map.of("temp", Double.NaN), t, 1, id()));
        assertEquals(IngestionStatus.REJECTED_INVALID, nan.ingestion().status());
    }
}
