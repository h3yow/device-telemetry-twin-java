package com.github.h3yow.devicetelemetrytwinjava.service;

import com.github.h3yow.devicetelemetrytwinjava.domain.Device;
import com.github.h3yow.devicetelemetrytwinjava.domain.EvaluationRecord;
import com.github.h3yow.devicetelemetrytwinjava.domain.IngestResult;
import com.github.h3yow.devicetelemetrytwinjava.domain.IngestStatus;
import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryReport;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceLocks;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceRegistry;
import com.github.h3yow.devicetelemetrytwinjava.store.ShadowStore;
import com.github.h3yow.devicetelemetrytwinjava.store.TelemetryHistoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 遥测接入服务：同一设备的乱序、重复、迟到上报在设备锁内串行识别与处理。
 * <p>
 * 处理顺序（每一步都产生可区分结论，绝不静默丢弃）：
 * <ol>
 *   <li>设备必须已注册，否则 REJECTED(UNKNOWN_DEVICE)；</li>
 *   <li>载荷必须非空且每个值为有限数，否则 REJECTED(INVALID_METRICS)；</li>
 *   <li>事件时间戳不得晚于接收时间 + 时钟偏移容忍，否则 REJECTED(FUTURE_TIMESTAMP)；</li>
 *   <li>seq 等于已接受的最大 seq：DUPLICATE（重传幂等忽略）；</li>
 *   <li>seq 小于最大 seq：STALE（乱序迟到，不覆盖新状态）；</li>
 *   <li>seq 更大但事件时间已超过迟到容忍窗口：STALE(LATE_ARRIVAL)，同样不写入；</li>
 *   <li>指标按注册区间清洗：全部越界 → REJECTED(OUT_OF_RANGE)；
 *       部分越界 → DEGRADED，仅清洗后的值写入影子；全部合法 → ACCEPTED；</li>
 *   <li>影子 reported 更新（版本 +1）与全量告警判定在同一临界区完成。</li>
 * </ol>
 */
@Service
public class TelemetryService {

    private final DeviceRegistry deviceRegistry;
    private final ShadowStore shadowStore;
    private final DeviceLocks deviceLocks;
    private final AlertService alertService;
    private final TelemetryHistoryStore historyStore;

    /** 事件时间晚于“已接受最新事件时间”多少毫秒仍允许接收（迟到容忍窗口）。 */
    private final long lateToleranceMillis;
    /** 事件时间领先接收时间多少毫秒视为“未来时间戳”（拒绝，防止时钟错乱污染状态）。 */
    private final long futureSkewMillis;

    public TelemetryService(DeviceRegistry deviceRegistry,
                            ShadowStore shadowStore,
                            DeviceLocks deviceLocks,
                            AlertService alertService,
                            TelemetryHistoryStore historyStore,
                            @Value("${telemetry.late-tolerance-millis:5000}") long lateToleranceMillis,
                            @Value("${telemetry.future-skew-millis:60000}") long futureSkewMillis) {
        this.deviceRegistry = deviceRegistry;
        this.shadowStore = shadowStore;
        this.deviceLocks = deviceLocks;
        this.alertService = alertService;
        this.historyStore = historyStore;
        this.lateToleranceMillis = lateToleranceMillis;
        this.futureSkewMillis = futureSkewMillis;
    }

    /** 接入结果，附带本次触发的告警判定记录（被拒绝/重复/迟到时为空）。 */
    public record IngestOutcome(IngestResult result, List<EvaluationRecord> evaluations) {
    }

    public IngestResult ingest(TelemetryReport report) {
        return ingestWithAlerts(report).result();
    }

    public IngestOutcome ingestWithAlerts(TelemetryReport report) {
        Device device = deviceRegistry.find(report.deviceId()).orElse(null);
        if (device == null) {
            return rejected(report, "UNKNOWN_DEVICE",
                    "设备未注册: " + report.deviceId());
        }
        if (report.metrics() == null || report.metrics().isEmpty()) {
            return rejected(report, "EMPTY_METRICS", "遥测载荷为空");
        }
        for (Map.Entry<String, Double> e : report.metrics().entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null
                    || !Double.isFinite(e.getValue())) {
                return rejected(report, "INVALID_METRICS",
                        "存在非法指标项: " + e.getKey() + "=" + e.getValue());
            }
        }
        if (report.ts() > report.receivedAt() + futureSkewMillis) {
            return rejected(report, "FUTURE_TIMESTAMP",
                    "事件时间戳 " + report.ts() + " 超过接收时间 " + report.receivedAt()
                            + " 的未来偏移容忍 " + futureSkewMillis + "ms，疑似设备时钟错乱");
        }

        ReentrantLock lock = deviceLocks.forDevice(report.deviceId());
        lock.lock();
        try {
            long maxSeq = shadowStore.lastSeq(report.deviceId());
            long maxTs = shadowStore.lastEventTs(report.deviceId());

            // seq < 0 表示由平台在临界区内分配单调序号（多接入线程并发时不会因抢锁顺序造成乱序）
            long effectiveSeq = report.seq() < 0 ? maxSeq + 1 : report.seq();

            if (maxSeq >= 0 && effectiveSeq == maxSeq) {
                return new IngestOutcome(IngestResult.of(IngestStatus.DUPLICATE,
                        "序列号 seq=" + effectiveSeq + " 与已接受的最新上报完全重复（重传幂等忽略）",
                        report, shadowStore.snapshot(report.deviceId()).reportedVersion()),
                        List.of());
            }
            if (effectiveSeq < maxSeq) {
                return new IngestOutcome(IngestResult.of(IngestStatus.STALE,
                        "乱序/迟到上报：seq=" + effectiveSeq + " 小于已接受最大 seq=" + maxSeq
                                + "，过期数据不得覆盖较新状态",
                        report, shadowStore.snapshot(report.deviceId()).reportedVersion()),
                        List.of());
            }
            // seq 更大（理论上更新），但事件时间明显早于已接受的最新事件：迟到数据
            if (maxTs >= 0 && report.ts() < maxTs - lateToleranceMillis) {
                return new IngestOutcome(IngestResult.of(IngestStatus.STALE,
                        "迟到上报：事件时间 ts=" + report.ts() + " 早于已接受最新 ts=" + maxTs
                                + " 且超出迟到容忍窗口 " + lateToleranceMillis + "ms",
                        report, shadowStore.snapshot(report.deviceId()).reportedVersion()),
                        List.of());
            }

            // 指标范围清洗
            Map<String, Double> cleaned = new HashMap<>();
            Map<String, String> dropped = new HashMap<>();
            for (Map.Entry<String, Double> e : report.metrics().entrySet()) {
                Device.MetricBounds bounds = device.metricBounds().get(e.getKey());
                if (bounds != null && !bounds.contains(e.getValue())) {
                    dropped.put(e.getKey(), e.getValue() + " 不在合法区间 ["
                            + bounds.min() + ", " + bounds.max() + "]");
                } else {
                    cleaned.put(e.getKey(), e.getValue());
                }
            }
            if (cleaned.isEmpty()) {
                return new IngestOutcome(IngestResult.of(IngestStatus.REJECTED,
                        "OUT_OF_RANGE: 全部指标越出合法区间，拒绝写入: " + dropped,
                        report, shadowStore.snapshot(report.deviceId()).reportedVersion()),
                        List.of());
            }

            long newVersion = shadowStore.applyReported(report.deviceId(), cleaned,
                    effectiveSeq, report.ts(), report.receivedAt());
            List<EvaluationRecord> evaluations = alertService.evaluate(
                    report.deviceId(), cleaned, report.receivedAt());
            // 留存用于规则试算的原始遥测：仅被接受（含降级）、指标已清洗的上报；
            // 被拒/重复/迟到的不进留存。使用清洗后的载荷，保证试算口径与线上判定一致。
            historyStore.append(new TelemetryReport(report.deviceId(), effectiveSeq,
                    report.ts(), cleaned, report.receivedAt()));

            IngestStatus status = dropped.isEmpty() ? IngestStatus.ACCEPTED : IngestStatus.DEGRADED;
            String reason = dropped.isEmpty()
                    ? "正常接收：seq=" + effectiveSeq + "，写入 reportedVersion=" + newVersion
                    : "降级接收：seq=" + effectiveSeq
                            + "，部分指标越界被清洗（不参与影子与告警）: " + dropped
                            + "；其余指标写入 reportedVersion=" + newVersion;
            return new IngestOutcome(IngestResult.of(status, reason, report, newVersion), evaluations);
        } finally {
            lock.unlock();
        }
    }

    private static IngestOutcome rejected(TelemetryReport report, String code, String reason) {
        return new IngestOutcome(
                new IngestResult(IngestStatus.REJECTED, code + ": " + reason,
                        report.deviceId(), report.seq(), -1L),
                List.of());
    }
}
