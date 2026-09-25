package com.github.h3yow.devicetelemetrytwinjava.simulator;

import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryReport;
import com.github.h3yow.devicetelemetrytwinjava.service.TelemetryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 进程内设备遥测模拟器：不依赖真实设备/MQTT/外部凭据，直接以方法调用方式把
 * “设备上报”送入 {@link TelemetryService}，模拟真实物联网平台的数据链路。
 * <p>
 * 默认关闭，通过 {@code simulator.enabled=true} 开启周期上报；测试中直接调用
 * 各个 produceXxx 方法构造乱序/重复/迟到/越界等场景。
 */
@Component
public class DeviceSimulator {

    public static final String DEVICE_A = "sim-thermo-001";
    public static final String DEVICE_B = "sim-thermo-002";

    private final TelemetryService telemetryService;
    private final boolean enabled;
    private long tick;

    public DeviceSimulator(TelemetryService telemetryService,
                           @org.springframework.beans.factory.annotation.Value("${simulator.enabled:false}")
                           boolean enabled) {
        this.telemetryService = telemetryService;
        this.enabled = enabled;
    }

    /** 推进一个周期：温度在阈值附近振荡，便于观察触发/恢复。仅在 simulator.enabled=true 时执行。 */
    @Scheduled(initialDelayString = "${simulator.initial-delay-ms:2000}",
            fixedDelayString = "${simulator.interval-ms:3000}")
    public void scheduledTick() {
        if (enabled) {
            tick();
        }
    }

    /** 手动推进一次模拟（测试与 REST 调用）。 */
    public TelemetryService.IngestOutcome tick() {
        tick++;
        // 温度序列：18,24,30,36,42,36,30,24,... 在 30 阈值上下往返
        double[] wave = {18, 24, 30, 36, 42, 36, 30, 24};
        double temp = wave[(int) ((tick - 1) % wave.length)];
        return produce(DEVICE_A, temp);
    }

    /**
     * 用指定温度为指定设备产生一次正常上报。
     * seq 传 -1 由平台在设备锁内分配单调序号，因此与 {@link #produceArbitrary} 手工
     * 指定的大 seq 互不干扰，模拟器自身的周期/tick 上报不会被误判为乱序。
     */
    public TelemetryService.IngestOutcome produce(String deviceId, double temp) {
        long now = System.currentTimeMillis();
        TelemetryReport report = new TelemetryReport(deviceId, -1L, now,
                Map.of("temperature", temp, "humidity", 55.0), now);
        return telemetryService.ingestWithAlerts(report);
    }

    /** 模拟重传：与上一条完全相同（相同 seq/ts/载荷）。 */
    public TelemetryService.IngestOutcome produceDuplicate(String deviceId,
                                                           long seq,
                                                           long ts,
                                                           Map<String, Double> metrics) {
        long now = System.currentTimeMillis();
        return telemetryService.ingestWithAlerts(
                new TelemetryReport(deviceId, seq, ts, metrics, now));
    }

    /** 模拟乱序/迟到：显式指定 seq 与事件时间戳。 */
    public TelemetryService.IngestOutcome produceArbitrary(String deviceId,
                                                           long seq,
                                                           long eventTs,
                                                           Map<String, Double> metrics) {
        return telemetryService.ingestWithAlerts(
                new TelemetryReport(deviceId, seq, eventTs, metrics, System.currentTimeMillis()));
    }
}
