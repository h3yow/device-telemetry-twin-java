package com.github.h3yow.devicetelemetrytwinjava.store;

import com.github.h3yow.devicetelemetrytwinjava.domain.HistoryPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 遥测历史留存：按设备保存最近被平台接受的遥测点，供规则试算回放。
 * <p>
 * 内存占用有明确上限，两级约束：
 * <ul>
 *   <li>每设备最多 {@code maxPointsPerDevice} 条，超出淘汰该设备最旧的点；</li>
 *   <li>全局最多 {@code maxTotalPoints} 条，超出按全局最旧优先淘汰（跨设备）。</li>
 * </ul>
 * 淘汰规则固定为“最旧先淘汰”，查询方可用 {@link #earliestRetainedTs(String)}
 * 区分“这段历史已被淘汰”与“这段时间确实没有数据”。
 * <p>
 * 所有方法内部加锁：写入发生在遥测接入的设备锁内，查询来自试算线程，
 * 二者通过本 store 的监视器串行化，读写均拿到一致快照。
 */
@Component
public class TelemetryHistoryStore {

    private final long maxPointsPerDevice;
    private final long maxTotalPoints;

    /** deviceId -> 该设备按追加顺序排列的历史点（队首最旧） */
    private final Map<String, Deque<HistoryPoint>> byDevice = new HashMap<>();
    /** 全局追加顺序索引（与 byDevice 中的元素是同一对象引用），用于全局最旧淘汰 */
    private final Deque<HistoryPoint> globalOrder = new ArrayDeque<>();
    /** deviceId -> 该设备被淘汰历史点的最大接收时间（淘汰高水位；从未淘汰则无记录） */
    private final Map<String, Long> maxEvictedReceivedAt = new HashMap<>();
    private long totalPoints;

    public TelemetryHistoryStore(
            @Value("${telemetry.history.max-points-per-device:50000}") long maxPointsPerDevice,
            @Value("${telemetry.history.max-total-points:500000}") long maxTotalPoints) {
        if (maxPointsPerDevice <= 0 || maxTotalPoints <= 0) {
            throw new IllegalArgumentException("历史留存上限必须为正数");
        }
        this.maxPointsPerDevice = maxPointsPerDevice;
        this.maxTotalPoints = maxTotalPoints;
    }

    /** 追加一条历史点（由遥测接入在设备锁内调用），随后按固定规则执行超限淘汰。 */
    public synchronized void append(HistoryPoint point) {
        Deque<HistoryPoint> deviceQueue =
                byDevice.computeIfAbsent(point.deviceId(), k -> new ArrayDeque<>());
        deviceQueue.addLast(point);
        globalOrder.addLast(point);
        totalPoints++;

        // 每设备超限：淘汰该设备最旧的点
        while (deviceQueue.size() > maxPointsPerDevice) {
            HistoryPoint evicted = deviceQueue.pollFirst();
            totalPoints--;
            markEvicted(evicted);
        }
        // 全局超限：按全局最旧优先淘汰；已被设备级淘汰的点（引用不再是对应队列队首）直接跳过
        while (totalPoints > maxTotalPoints) {
            HistoryPoint oldest = globalOrder.pollFirst();
            if (oldest == null) {
                break;
            }
            Deque<HistoryPoint> q = byDevice.get(oldest.deviceId());
            if (q != null && q.peekFirst() == oldest) {
                q.pollFirst();
                totalPoints--;
                markEvicted(oldest);
            }
        }
    }

    private void markEvicted(HistoryPoint point) {
        maxEvictedReceivedAt.merge(point.deviceId(), point.receivedAt(), Math::max);
    }

    /**
     * 查询设备在 [fromTs, toTs]（按平台接收时间 receivedAt）窗口内的历史点，
     * 按 (receivedAt, seq) 升序返回，保证同一输入下回放顺序确定。
     */
    public synchronized List<HistoryPoint> query(String deviceId, long fromTs, long toTs) {
        Deque<HistoryPoint> q = byDevice.get(deviceId);
        if (q == null || q.isEmpty()) {
            return List.of();
        }
        List<HistoryPoint> result = new ArrayList<>();
        for (HistoryPoint p : q) {
            if (p.receivedAt() >= fromTs && p.receivedAt() <= toTs) {
                result.add(p);
            }
        }
        result.sort((a, b) -> {
            int c = Long.compare(a.receivedAt(), b.receivedAt());
            return c != 0 ? c : Long.compare(a.seq(), b.seq());
        });
        return List.copyOf(result);
    }

    /** 该设备当前留存的最早历史点接收时间；无留存时返回 null。 */
    public synchronized Long earliestRetainedTs(String deviceId) {
        Deque<HistoryPoint> q = byDevice.get(deviceId);
        if (q == null || q.isEmpty()) {
            return null;
        }
        long min = Long.MAX_VALUE;
        for (HistoryPoint p : q) {
            min = Math.min(min, p.receivedAt());
        }
        return min;
    }

    /**
     * 该设备被淘汰历史点的最大接收时间；从未发生过淘汰时返回 null。
     * 用于区分“窗口内的历史已被淘汰”与“窗口起点只是早于设备开始有数据的时间”。
     */
    public synchronized Long maxEvictedReceivedAt(String deviceId) {
        return maxEvictedReceivedAt.get(deviceId);
    }

    /** 该设备当前留存点数。 */
    public synchronized int sizeOf(String deviceId) {
        Deque<HistoryPoint> q = byDevice.get(deviceId);
        return q == null ? 0 : q.size();
    }

    /** 全局当前留存点数。 */
    public synchronized long totalSize() {
        return totalPoints;
    }
}
