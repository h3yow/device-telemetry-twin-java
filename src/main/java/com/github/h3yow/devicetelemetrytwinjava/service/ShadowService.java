package com.github.h3yow.devicetelemetrytwinjava.service;

import com.github.h3yow.devicetelemetrytwinjava.domain.DeviceShadow;
import com.github.h3yow.devicetelemetrytwinjava.domain.ShadowUpdateResult;
import com.github.h3yow.devicetelemetrytwinjava.domain.ShadowUpdateStatus;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceLocks;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceRegistry;
import com.github.h3yow.devicetelemetrytwinjava.store.ShadowStore;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 设备影子应用服务：统一多入口（REST / 遥测管道内部）对影子的读写语义。
 * <ul>
 *   <li>读取：在设备锁内取不可变快照，保证读到的 reported/desired/版本号彼此自洽；</li>
 *   <li>desired 写入：先校验合法性，再以乐观版本号判定冲突，拒绝原因可区分；</li>
 *   <li>reported 写入仅供遥测管道在同一临界区调用（见 TelemetryService）。</li>
 * </ul>
 */
@Service
public class ShadowService {

    private final ShadowStore shadowStore;
    private final DeviceRegistry deviceRegistry;
    private final DeviceLocks deviceLocks;

    public ShadowService(ShadowStore shadowStore,
                         DeviceRegistry deviceRegistry,
                         DeviceLocks deviceLocks) {
        this.shadowStore = shadowStore;
        this.deviceRegistry = deviceRegistry;
        this.deviceLocks = deviceLocks;
    }

    /** 读取设备影子快照；设备未注册返回 null。 */
    public DeviceShadow getShadow(String deviceId) {
        if (!deviceRegistry.exists(deviceId)) {
            return null;
        }
        ReentrantLock lock = deviceLocks.forDevice(deviceId);
        lock.lock();
        try {
            return shadowStore.snapshot(deviceId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 期望状态写入。
     *
     * @param expectedVersion 客户端读取到的 desiredVersion；-1 表示不做版本校验
     */
    public ShadowUpdateResult writeDesired(String deviceId,
                                           Map<String, Double> desired,
                                           long expectedVersion) {
        if (!deviceRegistry.exists(deviceId)) {
            return new ShadowUpdateResult(ShadowUpdateStatus.UNKNOWN_DEVICE,
                    "设备不存在: " + deviceId, deviceId, -1L);
        }
        if (desired == null || desired.isEmpty()) {
            return new ShadowUpdateResult(ShadowUpdateStatus.INVALID_STATE,
                    "期望状态不能为空", deviceId, currentDesiredVersion(deviceId));
        }
        for (Map.Entry<String, Double> e : desired.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null
                    || !Double.isFinite(e.getValue())) {
                return new ShadowUpdateResult(ShadowUpdateStatus.INVALID_STATE,
                        "期望状态包含非法指标项: " + e.getKey() + "=" + e.getValue(),
                        deviceId, currentDesiredVersion(deviceId));
            }
        }
        ReentrantLock lock = deviceLocks.forDevice(deviceId);
        lock.lock();
        try {
            return shadowStore.updateDesired(deviceId, Map.copyOf(desired), expectedVersion,
                    System.currentTimeMillis());
        } finally {
            lock.unlock();
        }
    }

    private long currentDesiredVersion(String deviceId) {
        ReentrantLock lock = deviceLocks.forDevice(deviceId);
        lock.lock();
        try {
            return shadowStore.snapshot(deviceId).desiredVersion();
        } finally {
            lock.unlock();
        }
    }
}
