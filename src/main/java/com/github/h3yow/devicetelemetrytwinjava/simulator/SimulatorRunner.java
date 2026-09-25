package com.github.h3yow.devicetelemetrytwinjava.simulator;

import com.github.h3yow.devicetelemetrytwinjava.device.DeviceRegistry;
import com.github.h3yow.devicetelemetrytwinjava.rule.ComparisonOp;
import com.github.h3yow.devicetelemetrytwinjava.rule.RuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 可选的启动自演示：设置 simulator.demo-on-start=true 后，
 * 启动时自动注册演示设备/规则并跑一遍 乱序/重复/迟到 脚本。默认关闭，保证测试确定性。
 */
@Component
@ConditionalOnProperty(name = "simulator.demo-on-start", havingValue = "true")
public class SimulatorRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulatorRunner.class);

    public static final String DEMO_DEVICE = "demo-thermometer-01";
    public static final String DEMO_RULE = "temp-high";

    private final DeviceRegistry registry;
    private final RuleService ruleService;
    private final DeviceTelemetrySimulator simulator;

    public SimulatorRunner(DeviceRegistry registry, RuleService ruleService,
                           DeviceTelemetrySimulator simulator) {
        this.registry = registry;
        this.ruleService = ruleService;
        this.simulator = simulator;
    }

    @Override
    public void run(String... args) {
        registry.register(DEMO_DEVICE, Set.of("temp"));
        ruleService.register(DEMO_RULE, DEMO_DEVICE, "temp", ComparisonOp.GT, 90, 2_000);
        log.info("[启动演示] 开始投递演示数据");
        simulator.runDemoScenario(DEMO_DEVICE).forEach(o ->
                log.info("[启动演示结论] status={} 依据={} 告警事件数={}",
                        o.ingestion().status(), o.ingestion().reason(), o.alarmEvents().size()));
    }
}
