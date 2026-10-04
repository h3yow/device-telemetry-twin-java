package com.github.h3yow.devicetelemetrytwinjava.store;

import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryHistoryWindow;
import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryReport;
import com.github.h3yow.devicetelemetrytwinjava.domain.TrialCoverage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 有界原始遥测留存。
 *
 * <p>容量上限（内存占用明确可配）：
 * <ul>
 *   <li>每设备最多保留 {@code trial.history.max-points-per-device}（默认 20000）条；</li>
 *   <li>所有设备合计最多 {@code trial.history.max-total-points}（默认 200000）条。</li>
 * </ul>
 *
 * <p>淘汰规则固定且确定：超限时按“先入先出”从<b>最旧</b>数据开始淘汰——优先淘汰触发
 * 追加的该设备最旧点；若总量仍超限，再按各设备队首里事件时间最早者全局淘汰。
 * 每台设备维护“已淘汰数据的最大事件时间”高水位，使查询/试算能区分
 * {@link TrialCoverage#PARTIAL_EVICTED}（历史被淘汰）与 {@link TrialCoverage#NO_DATA}
 * （留存完整但窗口内确实没有数据）。
 *
 * <p>并发：每台设备一把锁，{@link #append} 与 {@link #window} 同设备串行、不同设备并行；
 * 全局总量淘汰由一把短临界区的总锁协调。该组件独立于线上设备锁，且只追加/淘汰自己的
 * 留存数据，绝不触碰影子与告警。
 */
@Component
public class TelemetryHistoryStore {

    private final int maxPointsPerDevice;
    private final int maxTotalPoints;

    private final Map<String, DeviceBuffer> buffers = new ConcurrentHashMap<>();
    private final AtomicInteger totalPoints = new AtomicInteger();
    /** 总量淘汰时的协调锁，仅保护“挑最旧设备队首并淘汰”的短过程。 */
    private final ReentrantLock globalEvictionLock = new ReentrantLock();

    public TelemetryHistoryStore(@Value("${trial.history.max-points-per-device:20000}") int maxPointsPerDevice,
                                 @Value("${trial.history.max-total-points:200000}") int maxTotalPoints) {
        if (maxPointsPerDevice <= 0 || maxTotalPoints <= 0) {
            throw new IllegalArgumentException("历史容量必须为正数: perDevice="
                    + maxPointsPerDevice + ", total=" + maxTotalPoints);
        }
        this.maxPointsPerDevice = maxPointsPerDevice;
        this.maxTotalPoints = maxTotalPoints;
    }

    /** 单设备留存缓冲与其淘汰高水位。所有字段访问均持有 {@link DeviceBuffer#lock}。 */
    private static final class DeviceBuffer {
        private final Deque<TelemetryReport> points = new ArrayDeque<>();
        private final ReentrantLock lock = new ReentrantLock();
        /** 已从本缓冲淘汰的数据的最大事件时间；-1 表示从未淘汰。 */
        private long evictedHighTs = -1L;
    }

    private DeviceBuffer buffer(String deviceId) {
        return buffers.computeIfAbsent(deviceId, k -> new DeviceBuffer());
    }

    /**
     * 追加一条已通过接入管道接受（含降级、指标已清洗）的遥测。
     * 追加后按容量规则做确定性淘汰。
     */
    public void append(TelemetryReport report) {
        if (report == null) {
            return;
        }
        DeviceBuffer buf = buffer(report.deviceId());
        buf.lock.lock();
        try {
            buf.points.addLast(report);
            totalPoints.incrementAndGet();
            // 设备容量：先从本设备最旧开始淘汰
            while (buf.points.size() > maxPointsPerDevice) {
                TelemetryReport evicted = buf.points.pollFirst();
                if (evicted == null) {
                    break;
                }
                recordEviction(buf, evicted.ts());
            }
        } finally {
            buf.lock.unlock();
        }
        // 全局容量：在所有设备队首中挑事件时间最早者淘汰，直到回到总量上限内
        evictToGlobalLimit();
    }

    private void recordEviction(DeviceBuffer buf, long evictedTs) {
        totalPoints.decrementAndGet();
        if (evictedTs > buf.evictedHighTs) {
            buf.evictedHighTs = evictedTs;
        }
    }

    private void evictToGlobalLimit() {
        globalEvictionLock.lock();
        try {
            // 简单的有界轮次保护：每轮至少淘汰一个点
            int guard = 0;
            int maxRounds = maxTotalPoints + 1;
            while (totalPoints.get() > maxTotalPoints && guard++ < maxRounds) {
                DeviceBuffer oldestBuffer = null;
                long oldestTs = Long.MAX_VALUE;
                for (DeviceBuffer b : buffers.values()) {
                    b.lock.lock();
                    try {
                        TelemetryReport head = b.points.peekFirst();
                        if (head != null && head.ts() < oldestTs) {
                            oldestTs = head.ts();
                            oldestBuffer = b;
                        }
                    } finally {
                        b.lock.unlock();
                    }
                }
                if (oldestBuffer == null) {
                    return;
                }
                oldestBuffer.lock.lock();
                try {
                    // 取锁后队首可能已变；重新确认仍为最旧且仍超总量再淘汰
                    TelemetryReport head = oldestBuffer.points.peekFirst();
                    if (head != null && totalPoints.get() > maxTotalPoints) {
                        oldestBuffer.points.pollFirst();
                        recordEviction(oldestBuffer, head.ts());
                    }
                } finally {
                    oldestBuffer.lock.unlock();
                }
            }
        } finally {
            globalEvictionLock.unlock();
        }
    }

    /**
     * 取设备窗口内遥测的不可变快照（按留存顺序），并给出覆盖情况与边界信息。只读。
     *
     * @param fromTs 窗口起点（事件时间，含）
     * @param toTs   窗口终点（事件时间，含）
     */
    public TelemetryHistoryWindow window(String deviceId, long fromTs, long toTs) {
        if (fromTs > toTs) {
            throw new IllegalArgumentException("时间窗口非法: fromTs=" + fromTs + " > toTs=" + toTs);
        }
        DeviceBuffer buf = buffers.get(deviceId);
        if (buf == null) {
            // 从未留存过：视为无淘汰、无数据
            return new TelemetryHistoryWindow(deviceId, fromTs, toTs, List.of(),
                    TrialCoverage.NO_DATA, null, null, null);
        }
        buf.lock.lock();
        try {
            Long oldestTs = buf.points.isEmpty() ? null : buf.points.peekFirst().ts();
            Long latestTs = buf.points.isEmpty() ? null : buf.points.peekLast().ts();
            Long evictedHighTs = buf.evictedHighTs < 0 ? null : buf.evictedHighTs;

            List<TelemetryReport> inWindow = new ArrayList<>();
            for (TelemetryReport r : buf.points) {
                if (r.ts() >= fromTs && r.ts() <= toTs) {
                    inWindow.add(r);
                }
            }

            TrialCoverage coverage;
            // 仅当请求窗口与“已淘汰数据的时间区间”（ts <= evictedHighTs）相交，才可能缺失数据。
            boolean intersectsEvictedRegion = evictedHighTs != null && fromTs <= evictedHighTs;
            if (!inWindow.isEmpty()) {
                // 窗口里有留存点：与淘汰区相交说明窗口前半段可能缺数据，否则就是完整的
                coverage = intersectsEvictedRegion ? TrialCoverage.PARTIAL_EVICTED : TrialCoverage.COMPLETE;
            } else {
                // 窗口内没有点：与淘汰区相交 => 可能被淘汰；否则留存完整但确实无数据
                coverage = intersectsEvictedRegion ? TrialCoverage.PARTIAL_EVICTED : TrialCoverage.NO_DATA;
            }
            return new TelemetryHistoryWindow(deviceId, fromTs, toTs,
                    List.copyOf(inWindow), coverage, evictedHighTs, oldestTs, latestTs);
        } finally {
            buf.lock.unlock();
        }
    }

    /** 当前留存总点数（主要用于测试与可观测）。 */
    public int totalPoints() {
        return totalPoints.get();
    }

    /** 当前某设备留存点数（主要用于测试）。 */
    public int pointsOf(String deviceId) {
        DeviceBuffer buf = buffers.get(deviceId);
        if (buf == null) {
            return 0;
        }
        buf.lock.lock();
        try {
            return buf.points.size();
        } finally {
            buf.lock.unlock();
        }
    }
}
