package com.github.h3yow.devicetelemetrytwinjava.web;

import com.github.h3yow.devicetelemetrytwinjava.service.TelemetryService;
import com.github.h3yow.devicetelemetrytwinjava.simulator.DeviceSimulator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟器入口：本地手动驱动数据链路。
 */
@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private final DeviceSimulator simulator;

    public SimulatorController(DeviceSimulator simulator) {
        this.simulator = simulator;
    }

    /** 推进一个温度振荡周期。 */
    @PostMapping("/tick")
    public TelemetryService.IngestOutcome tick() {
        return simulator.tick();
    }

    /** 为指定设备产生一条指定温度的上报。 */
    @PostMapping("/produce")
    public TelemetryService.IngestOutcome produce(@RequestBody ProduceRequest req) {
        return simulator.produce(req.deviceId(), req.temperature());
    }

    /** 产生一条任意 seq/ts 的上报，用于演示乱序/迟到。 */
    @PostMapping("/arbitrary")
    public TelemetryService.IngestOutcome arbitrary(@RequestBody ArbitraryRequest req) {
        return simulator.produceArbitrary(req.deviceId(), req.seq(), req.ts(), req.metrics());
    }

    /** 产生一条与给定内容完全相同的重传。 */
    @PostMapping("/duplicate")
    public TelemetryService.IngestOutcome duplicate(@RequestBody DuplicateRequest req) {
        return simulator.produceDuplicate(req.deviceId(), req.seq(), req.ts(), req.metrics());
    }

    public record ProduceRequest(String deviceId, double temperature) {
    }

    public record ArbitraryRequest(String deviceId,
                                   long seq,
                                   long ts,
                                   java.util.Map<String, Double> metrics) {
    }

    public record DuplicateRequest(String deviceId,
                                   long seq,
                                   long ts,
                                   java.util.Map<String, Double> metrics) {
    }
}
