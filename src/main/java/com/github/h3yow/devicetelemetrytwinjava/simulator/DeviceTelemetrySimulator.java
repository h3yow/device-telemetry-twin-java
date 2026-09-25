package com.github.h3yow.devicetelemetrytwinjava.simulator;

import com.github.h3yow.devicetelemetrytwinjava.pipeline.IngestionOutcome;
import com.github.h3yow.devicetelemetrytwinjava.pipeline.IngestionPipeline;
import com.github.h3yow.devicetelemetrytwinjava.telemetry.TelemetryMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 进程内遥测模拟源：在本地构造正常 / 乱序 / 重复 / 迟到上报，经与 HTTP 完全相同的
 * IngestionPipeline 入口投递，不依赖真实设备。
 */
@Component
public class DeviceTelemetrySimulator {

    private static final Logger log = LoggerFactory.getLogger(DeviceTelemetrySimulator.class);

    private final IngestionPipeline pipeline;

    public DeviceTelemetrySimulator(IngestionPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /** 直接投递一条构造好的报文（测试与自定义脚本使用）。 */
    public IngestionOutcome send(TelemetryMessage message) {
        return pipeline.ingest(message);
    }

    public static TelemetryMessage msg(String deviceId, long seq, long timestamp,
                                       String metric, double value) {
        return new TelemetryMessage(deviceId, Map.of(metric, value),
                timestamp, seq, UUID.randomUUID().toString());
    }

    public static TelemetryMessage msg(String deviceId, long seq, long timestamp,
                                       Map<String, Double> metrics, String messageId) {
        return new TelemetryMessage(deviceId, metrics, timestamp, seq, messageId);
    }

    /**
     * 预置演示脚本（事件时间相对当前时间构造）：
     * <pre>
     * seq=1 正常  temp=80
     * seq=3 跳号  temp=95  -> 触发高温告警
     * seq=2 乱序到达（旧 seq）-> 过期拒绝
     * seq=3 重发（同 messageId 同负载）-> 重复拒绝
     * seq=4 迟到（事件时间过旧）-> 降级接受
     * seq=5 正常  temp=80  -> 告警恢复
     * </pre>
     */
    public List<IngestionOutcome> runDemoScenario(String deviceId) {
        long now = System.currentTimeMillis();
        List<IngestionOutcome> outcomes = new ArrayList<>();

        TelemetryMessage s1 = msg(deviceId, 1, now - 5_000, "temp", 80);
        TelemetryMessage s3 = msg(deviceId, 3, now - 3_000, "temp", 95);
        TelemetryMessage s2 = msg(deviceId, 2, now - 4_000, "temp", 90);
        TelemetryMessage s3dup = new TelemetryMessage(deviceId, Map.of("temp", 95.0),
                s3.timestamp(), s3.seq(), s3.messageId());
        TelemetryMessage s4late = msg(deviceId, 4, now - 60_000, "temp", 99);
        TelemetryMessage s5 = msg(deviceId, 5, now - 1_000, "temp", 80);

        for (TelemetryMessage m : List.of(s1, s3, s2, s3dup, s4late, s5)) {
            log.info("[模拟器] 投递 deviceId={} seq={} eventTime={} metrics={}",
                    m.deviceId(), m.seq(), m.timestamp(), m.metrics());
            outcomes.add(pipeline.ingest(m));
        }
        return outcomes;
    }
}
