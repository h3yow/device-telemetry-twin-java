package com.github.h3yow.devicetelemetrytwinjava.pipeline;

import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmDecision;
import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmEvent;
import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmService;
import com.github.h3yow.devicetelemetrytwinjava.common.StripedDeviceLocks;
import com.github.h3yow.devicetelemetrytwinjava.shadow.DeviceShadowService;
import com.github.h3yow.devicetelemetrytwinjava.shadow.ShadowUpdateResult;
import com.github.h3yow.devicetelemetrytwinjava.telemetry.TelemetryIngestionService;
import com.github.h3yow.devicetelemetrytwinjava.telemetry.TelemetryMessage;
import com.github.h3yow.devicetelemetrytwinjava.telemetry.TelemetryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 接入编排：对同一设备加锁，串行执行 接入检查 -> 影子写入 -> 告警判定，
 * 保证多入口（HTTP / 模拟器）并发下结论一致、不重复告警、不漏报。
 *
 * <p>只有 ACCEPTED / ACCEPTED_GAP 的数据会写入影子并驱动告警；
 * ACCEPTED_LATE 仅留痕；各类 REJECTED 一律不产生副作用。
 */
@Service
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private final TelemetryIngestionService ingestion;
    private final DeviceShadowService shadow;
    private final AlarmService alarm;
    private final StripedDeviceLocks locks;

    public IngestionPipeline(TelemetryIngestionService ingestion,
                             DeviceShadowService shadow,
                             AlarmService alarm,
                             StripedDeviceLocks locks) {
        this.ingestion = ingestion;
        this.shadow = shadow;
        this.alarm = alarm;
        this.locks = locks;
    }

    public IngestionOutcome ingest(TelemetryMessage message) {
        var lock = locks.lockFor(message == null ? "<null>" : String.valueOf(message.deviceId()));
        lock.lock();
        try {
            log.info("[接入输入] {}", format(message));

            TelemetryResult result = ingestion.inspect(message);
            log.info("[接入结论] status={} appliedToShadow={} 依据={}",
                    result.status(), result.appliedToShadow(), result.reason());

            List<AlarmEvent> events = List.of();
            if (result.appliedToShadow()) {
                ShadowUpdateResult shadowResult = shadow.applyReported(message);
                if (!shadowResult.accepted()) {
                    // 影子层版本防护拦截：理论上接入层已拦截，出现时必须显式暴露，不静默
                    log.warn("[影子拒绝] {}", shadowResult.reason());
                    return new IngestionOutcome(result, List.of());
                }
                AlarmDecision decision = alarm.evaluate(message);
                events = decision.events();
                for (AlarmEvent e : events) {
                    log.info("[告警事件] type={} rule={} rev={} seq={} 依据={}",
                            e.type(), e.ruleId(), e.revision(), e.seq(), e.basis());
                }
                if (events.isEmpty()) {
                    log.info("[告警判定] 无状态迁移（规则未触发/未配置/指标缺失）");
                }
            }
            return new IngestionOutcome(result, events);
        } finally {
            lock.unlock();
        }
    }

    private static String format(TelemetryMessage m) {
        if (m == null) {
            return "null";
        }
        return String.format("deviceId=%s messageId=%s seq=%d eventTime=%d metrics=%s",
                m.deviceId(), m.messageId(), m.seq(), m.timestamp(), m.metrics());
    }
}
