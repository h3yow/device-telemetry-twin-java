package com.github.h3yow.devicetelemetrytwinjava.domain;

import java.util.Map;

/**
 * 设备注册信息（进程内注册中心条目）。
 *
 * @param deviceId     设备 ID
 * @param name         设备名称
 * @param metricBounds 指标合法区间（min/max）；键为指标名，缺失区间的指标不做范围清洗
 */
public record Device(String deviceId,
                     String name,
                     Map<String, MetricBounds> metricBounds) {

    public Device {
        metricBounds = metricBounds == null ? Map.of() : Map.copyOf(metricBounds);
    }

    /**
     * 指标合法区间。
     */
    public record MetricBounds(double min, double max) {
        public boolean contains(double value) {
            return value >= min && value <= max;
        }
    }
}
