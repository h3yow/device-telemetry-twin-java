package com.github.h3yow.devicetelemetrytwinjava.shadow;

import com.github.h3yow.devicetelemetrytwinjava.device.DeviceProfile;
import com.github.h3yow.devicetelemetrytwinjava.device.DeviceRegistry;
import com.github.h3yow.devicetelemetrytwinjava.telemetry.TelemetryMessage;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备影子服务：维护期望（desired）/上报（reported）两套独立版本状态。
 * <ul>
 *   <li>reported 版本 = 已接受遥测 seq；更旧 seq 一律拒绝（STALE），旧数据不覆盖新状态；</li>
 *   <li>desired 版本 = 每次成功下发递增；更新时用 expectedVersion 做乐观锁，
 *       不匹配返回 VERSION_CONFLICT，可区分于 STALE 与 INVALID_STATE；</li>
 *   <li>影子整体 version 在任一写入成功后递增，可解释状态演进顺序。</li>
 * </ul>
 * 所有方法都在调用方（pipeline / controller）持有的同设备锁内执行。
 */
@Service
public class DeviceShadowService {

    private final DeviceRegistry registry;
    private final ConcurrentHashMap<String, MutableShadow> shadows = new ConcurrentHashMap<>();

    public DeviceShadowService(DeviceRegistry registry) {
        this.registry = registry;
    }

    public Optional<ShadowState> get(String deviceId) {
        return Optional.ofNullable(shadows.get(deviceId)).map(MutableShadow::snapshot);
    }

    /** 由有效遥测驱动的 reported 写入。重复/过期由接入层先行拒绝，这里再做一次版本防护。 */
    public ShadowUpdateResult applyReported(TelemetryMessage message) {
        MutableShadow shadow = shadows.get(message.deviceId());
        if (shadow == null) {
            DeviceProfile profile = registry.find(message.deviceId()).orElse(null);
            if (profile == null) {
                return ShadowUpdateResult.rejected("UNKNOWN_DEVICE", null);
            }
            shadow = shadows.computeIfAbsent(message.deviceId(), k -> new MutableShadow(k));
        }
        if (shadow.reportedVersion >= 0 && message.seq() <= shadow.reportedVersion) {
            return ShadowUpdateResult.rejected(
                    "STALE: reportedVersion=" + shadow.reportedVersion
                            + "，写入 seq=" + message.seq() + " 已过期",
                    shadow.snapshot());
        }
        shadow.reported = Map.copyOf(message.metrics());
        shadow.reportedVersion = message.seq();
        shadow.version++;
        shadow.updatedAt = System.currentTimeMillis();
        return ShadowUpdateResult.ok(shadow.snapshot());
    }

    /**
     * 期望状态下发（乐观并发）。
     *
     * @param expectedVersion 调用方读取到的 desiredVersion；传 -1 表示不校验（盲写）
     */
    public ShadowUpdateResult updateDesired(String deviceId, Map<String, Double> desired, long expectedVersion) {
        DeviceProfile profile = registry.find(deviceId).orElse(null);
        if (profile == null) {
            return ShadowUpdateResult.rejected("UNKNOWN_DEVICE", null);
        }
        if (desired == null) {
            return ShadowUpdateResult.rejected("INVALID_STATE: desired 为 null",
                    get(deviceId).orElse(null));
        }
        for (Map.Entry<String, Double> e : desired.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                return ShadowUpdateResult.rejected("INVALID_STATE: 存在空指标名",
                        get(deviceId).orElse(null));
            }
            if (e.getValue() == null || !Double.isFinite(e.getValue())) {
                return ShadowUpdateResult.rejected(
                        "INVALID_STATE: 指标 " + e.getKey() + " 值非法 " + e.getValue(),
                        get(deviceId).orElse(null));
            }
            if (!profile.allowedMetrics().contains(e.getKey())) {
                return ShadowUpdateResult.rejected(
                        "INVALID_STATE: 指标 " + e.getKey() + " 不在设备允许集合 "
                                + profile.allowedMetrics(),
                        get(deviceId).orElse(null));
            }
        }

        MutableShadow shadow = shadows.computeIfAbsent(deviceId, MutableShadow::new);
        if (expectedVersion >= 0 && expectedVersion != shadow.desiredVersion) {
            return ShadowUpdateResult.rejected(
                    "VERSION_CONFLICT: 期望版本=" + shadow.desiredVersion
                            + "，提交基于=" + expectedVersion,
                    shadow.snapshot());
        }
        shadow.desired = Map.copyOf(desired);
        shadow.desiredVersion++;
        shadow.version++;
        shadow.updatedAt = System.currentTimeMillis();
        return ShadowUpdateResult.ok(shadow.snapshot());
    }

    private static final class MutableShadow {
        final String deviceId;
        long version;
        Map<String, Double> desired = Collections.emptyMap();
        long desiredVersion = 0;
        Map<String, Double> reported = Collections.emptyMap();
        long reportedVersion = -1;
        long updatedAt;

        MutableShadow(String deviceId) {
            this.deviceId = deviceId;
        }

        synchronized ShadowState snapshot() {
            return new ShadowState(deviceId, version,
                    Map.copyOf(desired), desiredVersion,
                    Map.copyOf(reported), reportedVersion, updatedAt);
        }
    }
}
