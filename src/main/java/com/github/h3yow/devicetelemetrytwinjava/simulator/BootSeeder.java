package com.github.h3yow.devicetelemetrytwinjava.simulator;

import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.Device;
import com.github.h3yow.devicetelemetrytwinjava.service.AlertService;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动时播种两台模拟设备与一条温度阈值规则，保证本地运行即可看到完整数据链路。
 */
@Component
public class BootSeeder implements CommandLineRunner {

    private final DeviceRegistry deviceRegistry;
    private final AlertService alertService;

    public BootSeeder(DeviceRegistry deviceRegistry, AlertService alertService) {
        this.deviceRegistry = deviceRegistry;
        this.alertService = alertService;
    }

    @Override
    public void run(String... args) {
        Device.MetricBounds tempBounds = new Device.MetricBounds(-40, 85);
        Device.MetricBounds humidityBounds = new Device.MetricBounds(0, 100);
        deviceRegistry.register(new Device(DeviceSimulator.DEVICE_A, "1号车间温度计",
                Map.of("temperature", tempBounds, "humidity", humidityBounds)));
        deviceRegistry.register(new Device(DeviceSimulator.DEVICE_B, "2号车间温度计",
                Map.of("temperature", tempBounds, "humidity", humidityBounds)));

        alertService.putRule("rule-temp-high", DeviceSimulator.DEVICE_A,
                "temperature", AlertRule.Operator.GT, 30.0, true);
    }
}
