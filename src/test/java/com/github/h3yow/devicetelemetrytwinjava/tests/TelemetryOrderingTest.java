package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.github.h3yow.devicetelemetrytwinjava.domain.Device;
import com.github.h3yow.devicetelemetrytwinjava.domain.IngestStatus;
import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryReport;
import com.github.h3yow.devicetelemetrytwinjava.service.AlertService;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 乱序、重复、迟到、降级与非法上报处理测试。
 */
class TelemetryOrderingTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryOrderingTest.class);
    private static final String DEVICE = "dev-order";

    private DeviceRegistry registry;
    private ShadowStore shadowStore;
    private TelemetryService telemetryService;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        shadowStore = new ShadowStore();
        DeviceLocks locks = new DeviceLocks();
        AlertStore alertStore = new AlertStore();
        AlertService alertService = new AlertService(alertStore, registry, locks);
        telemetryService = new TelemetryService(registry, shadowStore, locks, alertService, new TelemetryHistoryStore(20000, 200000), 5000, 60000);
        registry.register(new Device(DEVICE, "排序测试设备",
                Map.of("temperature", new Device.MetricBounds(-40, 85),
                        "humidity", new Device.MetricBounds(0, 100))));
    }

    private TelemetryService.IngestOutcome send(long seq, long eventOffsetMs, Map<String, Double> m) {
        long now = System.currentTimeMillis();
        TelemetryReport r = new TelemetryReport(DEVICE, seq, now + eventOffsetMs, m, now);
        TelemetryService.IngestOutcome out = telemetryService.ingestWithAlerts(r);
        log.info("[输入] device={} seq={} ts偏移={}ms metrics={} -> [结论] status={} version={} reason={}",
                DEVICE, seq, eventOffsetMs, m, out.result().status(),
                out.result().shadowVersion(), out.result().reason());
        return out;
    }

    @Test
    @DisplayName("正常 -> 重复(同seq) -> 乱序(小seq) -> 迟到(seq大但ts老): 结论可区分且旧数据不覆盖新状态")
    void duplicateOutOfOrderAndLate() {
        var first = send(10, 0, Map.of("temperature", 20.0));
        assertEquals(IngestStatus.ACCEPTED, first.result().status());
        assertEquals(1, first.result().shadowVersion());

        // 完全重传：seq 相同
        var dup = send(10, 0, Map.of("temperature", 20.0));
        assertEquals(IngestStatus.DUPLICATE, dup.result().status());
        assertEquals(1, dup.result().shadowVersion(), "重复上报不得推进版本");

        // 更新的一条
        var second = send(11, 10, Map.of("temperature", 21.0));
        assertEquals(IngestStatus.ACCEPTED, second.result().status());
        assertEquals(2, second.result().shadowVersion());

        // 乱序迟到：seq 回退到 9
        var outOfOrder = send(9, -100, Map.of("temperature", 99.0));
        assertEquals(IngestStatus.STALE, outOfOrder.result().status());
        assertEquals(2, outOfOrder.result().shadowVersion());

        // seq 更大但事件时间老于最新事件超过容忍窗口：仍是迟到
        var late = send(12, -10_000, Map.of("temperature", -50.0));
        assertEquals(IngestStatus.STALE, late.result().status());
        assertEquals(2, late.result().shadowVersion());

        // 最终影子必须是 seq=11 的值，过期数据没有覆盖
        var shadow = shadowStore.snapshot(DEVICE);
        assertEquals(21.0, shadow.reported().get("temperature"));
        assertEquals(11, shadow.lastSeq());
        log.info("[影子最终状态] {}", shadow);
    }

    @Test
    @DisplayName("部分指标越界 -> DEGRADED 仅写入合法指标；全部越界 -> REJECTED")
    void degradedAndOutOfRange() {
        var degraded = send(20, 0, Map.of("temperature", 25.0, "humidity", 250.0));
        assertEquals(IngestStatus.DEGRADED, degraded.result().status());
        var shadow = shadowStore.snapshot(DEVICE);
        assertEquals(25.0, shadow.reported().get("temperature"));
        assertTrue(!shadow.reported().containsKey("humidity"), "越界指标必须被清洗，不进入影子");
        assertEquals(1, shadow.reportedVersion(), "降级接收仍推进一次 reported 版本");
        log.info("[降级结论] {} / 影子={}", degraded.result(), shadow);

        // 同一设备再来一条全部指标越界：REJECTED，版本不推进
        var allBad = send(21, 0, Map.of("temperature", 500.0, "humidity", 500.0));
        assertEquals(IngestStatus.REJECTED, allBad.result().status());
        assertTrue(allBad.result().reason().contains("OUT_OF_RANGE"));
        assertEquals(1, shadowStore.snapshot(DEVICE).reportedVersion());
        log.info("[全部越界结论] {}", allBad.result());
    }

    @Test
    @DisplayName("未知设备 / 空载荷 / NaN / 未来时间戳: 一律 REJECTED 且原因可区分")
    void invalidReportsRejected() {
        long now = System.currentTimeMillis();

        var unknown = telemetryService.ingestWithAlerts(new TelemetryReport(
                "no-such-device", 1, now, Map.of("temperature", 1.0), now));
        assertEquals(IngestStatus.REJECTED, unknown.result().status());
        assertTrue(unknown.result().reason().contains("UNKNOWN_DEVICE"));

        var empty = telemetryService.ingestWithAlerts(new TelemetryReport(
                DEVICE, 1, now, Map.of(), now));
        assertEquals(IngestStatus.REJECTED, empty.result().status());
        assertTrue(empty.result().reason().contains("EMPTY_METRICS"));

        var nan = telemetryService.ingestWithAlerts(new TelemetryReport(
                DEVICE, 1, now, Map.of("temperature", Double.NaN), now));
        assertEquals(IngestStatus.REJECTED, nan.result().status());
        assertTrue(nan.result().reason().contains("INVALID_METRICS"));

        var future = telemetryService.ingestWithAlerts(new TelemetryReport(
                DEVICE, 1, now + 999_999, Map.of("temperature", 1.0), now));
        assertEquals(IngestStatus.REJECTED, future.result().status());
        assertTrue(future.result().reason().contains("FUTURE_TIMESTAMP"));
        log.info("[非法上报结论] unknown={} empty={} nan={} future={}",
                unknown.result().reason(), empty.result().reason(),
                nan.result().reason(), future.result().reason());
    }
}
