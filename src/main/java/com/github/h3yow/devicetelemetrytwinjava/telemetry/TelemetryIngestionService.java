package com.github.h3yow.devicetelemetrytwinjava.telemetry;

import com.github.h3yow.devicetelemetrytwinjava.common.IngestionStatus;
import com.github.h3yow.devicetelemetrytwinjava.device.DeviceProfile;
import com.github.h3yow.devicetelemetrytwinjava.device.DeviceRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 遥测接入：报文校验、幂等去重、乱序/过期识别、迟到降级。
 * 本服务只产出接入结论；影子写入与告警判定由 pipeline 在同一设备锁内编排，
 * 因此本类的状态检查/更新无需自行加锁（调用方保证同设备串行）。
 *
 * <p>每设备维护：
 * <ul>
 *   <li>lastSeq / lastDigest：已接受的最大序列号及其负载指纹，用于重复、过期、冲突识别；</li>
 *   <li>lastEventTime：已接受数据的最大事件时间，据此维护事件时间水位，识别迟到数据；</li>
 *   <li>processedMessageIds：已处理 messageId -> 负载指纹，做幂等去重。</li>
 * </ul>
 */
@Service
public class TelemetryIngestionService {

    private final DeviceRegistry registry;
    private final long lateMillis;
    private final ConcurrentHashMap<String, DeviceIngestState> states = new ConcurrentHashMap<>();

    public TelemetryIngestionService(
            DeviceRegistry registry,
            @Value("${telemetry.late-millis:3000}") long lateMillis) {
        this.registry = registry;
        this.lateMillis = lateMillis;
    }

    public TelemetryResult inspect(TelemetryMessage message) {
        // 1. 结构与合法性校验
        String invalid = validateStructure(message);
        if (invalid != null) {
            return reject(message, IngestionStatus.REJECTED_INVALID, invalid);
        }

        // 2. 设备注册校验
        DeviceProfile profile = registry.find(message.deviceId()).orElse(null);
        if (profile == null) {
            return reject(message, IngestionStatus.REJECTED_UNKNOWN_DEVICE,
                    "deviceId=" + message.deviceId() + " 未注册");
        }

        // 3. 指标白名单校验
        if (!profile.allowedMetrics().containsAll(message.metrics().keySet())) {
            return reject(message, IngestionStatus.REJECTED_INVALID,
                    "上报指标 " + message.metrics().keySet()
                            + " 超出设备允许集合 " + profile.allowedMetrics());
        }

        String digest = digest(message.metrics());
        DeviceIngestState state = states.computeIfAbsent(
                message.deviceId(), k -> new DeviceIngestState());

        // 4. messageId 幂等去重（重发/重复上报）
        String seenDigest = state.messageIds.get(message.messageId());
        if (seenDigest != null) {
            if (seenDigest.equals(digest)) {
                return reject(message, IngestionStatus.REJECTED_DUPLICATE,
                        "messageId=" + message.messageId() + " 已处理过，seq="
                                + message.seq() + "，负载一致：重复上报");
            }
            return reject(message, IngestionStatus.REJECTED_DUPLICATE,
                    "messageId=" + message.messageId()
                            + " 已被其他负载占用：messageId 复用冲突，按重复拒绝");
        }

        // 5. 序列号乱序/过期/冲突判定
        if (state.initialized) {
            if (message.seq() < state.lastSeq) {
                return reject(message, IngestionStatus.REJECTED_STALE,
                        "seq=" + message.seq() + " 落后于已接受位点 lastSeq="
                                + state.lastSeq + "：过期写入，不覆盖状态");
            }
            if (message.seq() == state.lastSeq) {
                if (digest.equals(state.lastDigest)) {
                    return reject(message, IngestionStatus.REJECTED_DUPLICATE,
                            "seq=" + message.seq() + " 与最新位点相同且负载一致：重复上报");
                }
                return reject(message, IngestionStatus.REJECTED_CONFLICT,
                        "seq=" + message.seq() + " 与最新位点相同但负载不一致：版本冲突");
            }
        }

        // 6. 迟到降级：事件时间低于事件时间水位允许的迟滞范围
        if (state.initialized && state.lastEventTime != Long.MIN_VALUE) {
            long watermark = state.lastEventTime - lateMillis;
            if (message.timestamp() < watermark) {
                // 迟到数据留痕：记录 messageId 防止重放，但不推进位点、不覆盖状态
                state.messageIds.put(message.messageId(), digest);
                return new TelemetryResult(message, IngestionStatus.ACCEPTED_LATE,
                        "eventTime=" + message.timestamp() + " 低于水位 " + watermark
                                + "（lastEventTime=" + state.lastEventTime
                                + "，迟滞窗口=" + lateMillis + "ms）：降级接受，仅留痕不覆盖影子",
                        false);
            }
        }

        // 7. 正常接受；检测序列号跳号（gap）
        long prevSeq = state.lastSeq;
        boolean gap = state.initialized && message.seq() > prevSeq + 1;
        state.lastSeq = message.seq();
        state.lastDigest = digest;
        state.lastEventTime = Math.max(state.lastEventTime, message.timestamp());
        state.messageIds.put(message.messageId(), digest);
        state.initialized = true;

        IngestionStatus status = gap ? IngestionStatus.ACCEPTED_GAP : IngestionStatus.ACCEPTED;
        String reason = gap
                ? "seq=" + message.seq() + " 接入，但检测到跳号（上一 seq="
                        + prevSeq + "，期望 " + (message.seq() - 1)
                        + "）：可能存在丢失上报"
                : "seq=" + message.seq() + " 正常接入，事件时间="
                        + message.timestamp() + "，指标=" + message.metrics();
        return new TelemetryResult(message, status, reason, true);
    }

    private static TelemetryResult reject(TelemetryMessage message, IngestionStatus status, String reason) {
        return new TelemetryResult(message, status, reason, false);
    }

    private static String validateStructure(TelemetryMessage m) {
        if (m == null) {
            return "报文为空";
        }
        if (m.deviceId() == null || m.deviceId().isBlank()) {
            return "deviceId 为空";
        }
        if (m.messageId() == null || m.messageId().isBlank()) {
            return "messageId 为空";
        }
        if (m.metrics() == null || m.metrics().isEmpty()) {
            return "metrics 为空";
        }
        if (m.timestamp() <= 0) {
            return "timestamp 非法: " + m.timestamp();
        }
        if (m.seq() < 0) {
            return "seq 非法: " + m.seq();
        }
        for (Map.Entry<String, Double> e : m.metrics().entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                return "存在空指标名";
            }
            if (e.getValue() == null || !Double.isFinite(e.getValue())) {
                return "指标 " + e.getKey() + " 的值非法: " + e.getValue();
            }
        }
        return null;
    }

    /** 负载指纹：按指标名排序后拼接，使指纹与 Map 迭代顺序无关。 */
    static String digest(Map<String, Double> metrics) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : new TreeMap<>(metrics).entrySet()) {
            sb.append(e.getKey()).append('=').append(Double.toString(e.getValue())).append(';');
        }
        return sb.toString();
    }

    private static final class DeviceIngestState {
        volatile boolean initialized = false;
        volatile long lastSeq = -1;
        volatile String lastDigest = "";
        volatile long lastEventTime = Long.MIN_VALUE;
        final ConcurrentHashMap<String, String> messageIds = new ConcurrentHashMap<>();
    }
}
