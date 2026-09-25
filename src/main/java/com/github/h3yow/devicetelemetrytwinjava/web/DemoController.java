package com.github.h3yow.devicetelemetrytwinjava.web;

import com.github.h3yow.devicetelemetrytwinjava.device.DeviceRegistry;
import com.github.h3yow.devicetelemetrytwinjava.pipeline.IngestionOutcome;
import com.github.h3yow.devicetelemetrytwinjava.rule.ComparisonOp;
import com.github.h3yow.devicetelemetrytwinjava.rule.RuleService;
import com.github.h3yow.devicetelemetrytwinjava.simulator.DeviceTelemetrySimulator;
import com.github.h3yow.devicetelemetrytwinjava.simulator.SimulatorRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 本地一键演示入口：GET /api/demo 注册演示设备/规则并跑乱序/重复/迟到脚本。
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DeviceRegistry registry;
    private final RuleService ruleService;
    private final DeviceTelemetrySimulator simulator;

    public DemoController(DeviceRegistry registry, RuleService ruleService,
                          DeviceTelemetrySimulator simulator) {
        this.registry = registry;
        this.ruleService = ruleService;
        this.simulator = simulator;
    }

    @GetMapping
    public List<IngestionOutcome> demo() {
        String deviceId = SimulatorRunner.DEMO_DEVICE;
        registry.find(deviceId).orElseGet(() ->
                registry.register(deviceId, Set.of("temp")));
        if (ruleService.current(SimulatorRunner.DEMO_RULE).isEmpty()) {
            ruleService.register(SimulatorRunner.DEMO_RULE, deviceId, "temp",
                    ComparisonOp.GT, 90, 2_000);
        }
        return simulator.runDemoScenario(deviceId);
    }
}
