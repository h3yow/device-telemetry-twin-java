package com.github.h3yow.devicetelemetrytwinjava.store;

import com.github.h3yow.devicetelemetrytwinjava.domain.DeviceShadow;
import com.github.h3yow.devicetelemetrytwinjava.domain.ShadowUpdateResult;
import com.github.h3yow.devicetelemetrytwinjava.domain.ShadowUpdateStatus;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备影子存储。
 * <p>
 * 每个设备一个可变内部状态 {@link MutableShadow}；<b>所有对单个设备状态的读写都必须持有
 * {@link DeviceLocks#forDevice(String)} 对应的锁</b>，从而保证多入口并发下：
 * <ul>
 *   <li>读永远拿到一份自洽快照（reported / desired / 版本号一致）；</li>
 *   <li>reported 版本严格按接受次数 +1，迟到/重复数据在 {@code TelemetryService} 被拦截，不会进入本存储；</li>
 *   <li>desired 写入采用乐观版本校验（expectedVersion 不匹配则 {@link ShadowUpdateStatus#VERSION_CONFLICT}）。</li>
 * </ul>
 * 快照通过防御性拷贝暴露，外部修改永远无法污染存储内部状态。
 */
@Component
public class ShadowStore {

    /** deviceId -> 影子。锁粒度是每设备一把，但本表跨设备共享，必须用并发容器。 */
    private final Map<String, MutableShadow> shadows = new ConcurrentHashMap<>();

    private MutableShadow internal(String deviceId) {
        return shadows.computeIfAbsent(deviceId, MutableShadow::new);
    }

    /** 读取当前影子的不可变快照（调用方须持有设备锁）。 */
    public DeviceShadow snapshot(String deviceId) {
        return internal(deviceId).snapshot();
    }

    /**
     * 应用一条已被遥测管道接受（含降级）的上报：reported 合并更新、版本 +1。
     *
     * @return 写入后的新版本号（reportedVersion）
     */
    public long applyReported(String deviceId,
                              Map<String, Double> metrics,
                              long seq,
                              long eventTs,
                              long now) {
        MutableShadow s = internal(deviceId);
        s.reported.putAll(metrics);
        s.lastSeq = seq;
        s.lastEventTs = eventTs;
        s.reportedVersion++;
        s.updatedAt = now;
        return s.reportedVersion;
    }

    public long lastSeq(String deviceId) {
        return internal(deviceId).lastSeq;
    }

    public long lastEventTs(String deviceId) {
        return internal(deviceId).lastEventTs;
    }

    /**
     * 期望状态写入（乐观并发）。
     *
     * @param expectedVersion 客户端持有的 desiredVersion；为 -1 表示不校验
     */
    public ShadowUpdateResult updateDesired(String deviceId,
                                            Map<String, Double> desired,
                                            long expectedVersion,
                                            long now) {
        MutableShadow s = internal(deviceId);
        if (expectedVersion >= 0 && expectedVersion != s.desiredVersion) {
            return new ShadowUpdateResult(ShadowUpdateStatus.VERSION_CONFLICT,
                    "期望版本不匹配：expectedVersion=" + expectedVersion
                            + "，当前 desiredVersion=" + s.desiredVersion,
                    deviceId, s.desiredVersion);
        }
        s.desired.clear();
        s.desired.putAll(desired);
        s.desiredVersion++;
        s.updatedAt = now;
        return new ShadowUpdateResult(ShadowUpdateStatus.OK,
                "期望状态写入成功，新版本=" + s.desiredVersion,
                deviceId, s.desiredVersion);
    }

    /** 可变内部状态，仅在设备锁内访问。 */
    private static final class MutableShadow {
        private final String deviceId;
        private final Map<String, Double> reported = new HashMap<>();
        private final Map<String, Double> desired = new HashMap<>();
        private long reportedVersion;
        private long desiredVersion;
        private long lastSeq = -1L;
        private long lastEventTs = -1L;
        private long updatedAt;

        private MutableShadow(String deviceId) {
            this.deviceId = deviceId;
        }

        private DeviceShadow snapshot() {
            return new DeviceShadow(deviceId,
                    Map.copyOf(reported),
                    Map.copyOf(desired),
                    reportedVersion,
                    desiredVersion,
                    lastSeq,
                    lastEventTs,
                    updatedAt);
        }
    }
}
