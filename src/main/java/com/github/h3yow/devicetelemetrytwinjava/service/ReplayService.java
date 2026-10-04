package com.github.h3yow.devicetelemetrytwinjava.service;

import com.github.h3yow.devicetelemetrytwinjava.domain.AlertView;
import com.github.h3yow.devicetelemetrytwinjava.domain.HistoryPoint;
import com.github.h3yow.devicetelemetrytwinjava.domain.ReplayAlert;
import com.github.h3yow.devicetelemetrytwinjava.domain.ReplayDiff;
import com.github.h3yow.devicetelemetrytwinjava.domain.ReplayResult;
import com.github.h3yow.devicetelemetrytwinjava.domain.ReplayRuleSpec;
import com.github.h3yow.devicetelemetrytwinjava.store.AlertStore;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceLocks;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceRegistry;
import com.github.h3yow.devicetelemetrytwinjava.store.TelemetryHistoryStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 规则试算服务：用留存的历史遥测对候选规则做纯函数式回放，
 * 并与同一时间窗口下线上生效规则的结果对照，输出差异。
 * <p>
 * 关键性质：
 * <ul>
 *   <li><b>只读</b>：不触碰影子、告警实体与判定历史；仅在拷贝线上告警快照的
 *       瞬间持有设备锁，回放计算全部在锁外的不可变数据上进行，不阻塞线上接入；</li>
 *   <li><b>可复现</b>：回放不依赖外部时钟与任何可变状态，同一批历史 +
 *       同一份候选规则必然得到完全相同的结果；</li>
 *   <li><b>语义一致</b>：判定时刻取历史点的平台接收时间、输入取清洗后的指标，
 *       与线上 {@code AlertService} 的触发/恢复语义一致（试算不建模人工抑制操作，
 *       抑制属于运维动作，无法从历史数据复现）。</li>
 * </ul>
 */
@Service
public class ReplayService {

    private final TelemetryHistoryStore historyStore;
    private final AlertStore alertStore;
    private final DeviceRegistry deviceRegistry;
    private final DeviceLocks deviceLocks;

    public ReplayService(TelemetryHistoryStore historyStore,
                         AlertStore alertStore,
                         DeviceRegistry deviceRegistry,
                         DeviceLocks deviceLocks) {
        this.historyStore = historyStore;
        this.alertStore = alertStore;
        this.deviceRegistry = deviceRegistry;
        this.deviceLocks = deviceLocks;
    }

    /**
     * 对指定设备、时间窗口与候选规则集做试算，并与线上结果对比。
     *
     * @param deviceId       设备 ID（必须已注册）
     * @param fromTs         窗口起点（按平台接收时间，含）
     * @param toTs           窗口终点（含）
     * @param candidateRules 候选规则集（可为尚未生效的阈值/算子）
     */
    public ReplayResult replay(String deviceId, long fromTs, long toTs,
                               List<ReplayRuleSpec> candidateRules) {
        if (!deviceRegistry.exists(deviceId)) {
            throw new IllegalArgumentException("设备不存在: " + deviceId);
        }
        if (fromTs > toTs) {
            throw new IllegalArgumentException("窗口起点不能晚于终点: fromTs=" + fromTs + " > toTs=" + toTs);
        }
        if (candidateRules == null || candidateRules.isEmpty()) {
            throw new IllegalArgumentException("候选规则集不能为空");
        }
        Map<String, ReplayRuleSpec> specsById = new LinkedHashMap<>();
        for (ReplayRuleSpec spec : candidateRules) {
            validate(spec);
            if (specsById.putIfAbsent(spec.ruleId(), spec) != null) {
                throw new IllegalArgumentException("候选规则 ruleId 重复: " + spec.ruleId());
            }
        }

        // 1) 取历史（不可变快照）与线上告警快照；快照拷贝在设备锁内完成，
        //    避免读到告警实体被并发修改的中间态，锁内不做任何回放计算
        List<HistoryPoint> points = historyStore.query(deviceId, fromTs, toTs);
        Long earliest = historyStore.earliestRetainedTs(deviceId);
        Long maxEvicted = historyStore.maxEvictedReceivedAt(deviceId);
        List<AlertView> liveAlerts;
        ReentrantLock lock = deviceLocks.forDevice(deviceId);
        lock.lock();
        try {
            liveAlerts = alertStore.alertsForDevice(deviceId).stream()
                    .filter(a -> a.triggeredAt() <= toTs
                            && (a.resolvedAt() == null || a.resolvedAt() >= fromTs))
                    .sorted(Comparator.comparingLong(AlertView::triggeredAt)
                            .thenComparing(AlertView::alertId))
                    .toList();
        } finally {
            lock.unlock();
        }

        // 2) 纯函数回放（锁外，不阻塞线上）
        List<ReplayAlert> candidateAlerts = new ArrayList<>();
        for (ReplayRuleSpec spec : specsById.values()) {
            candidateAlerts.addAll(replayRule(spec, points));
        }
        candidateAlerts.sort(Comparator.comparingLong(ReplayAlert::triggeredAt)
                .thenComparing(ReplayAlert::ruleId));

        // 3) 差异对照
        List<ReplayDiff> diffs = diff(specsById, candidateAlerts, liveAlerts);

        // 覆盖情况：窗口与“已淘汰区域”重叠才算 PARTIAL_EVICTED；
        // 窗口起点早于最早留存点但从未发生淘汰，只是设备还没有那么早的数据，仍属 FULL
        ReplayResult.Coverage coverage;
        if (maxEvicted != null && fromTs <= maxEvicted) {
            coverage = ReplayResult.Coverage.PARTIAL_EVICTED;
        } else if (earliest == null) {
            coverage = ReplayResult.Coverage.EMPTY;
        } else {
            coverage = ReplayResult.Coverage.FULL;
        }
        return new ReplayResult(deviceId, fromTs, toTs, coverage, earliest,
                points.size(), List.copyOf(candidateAlerts), liveAlerts, List.copyOf(diffs));
    }

    private static void validate(ReplayRuleSpec spec) {
        if (spec.ruleId() == null || spec.ruleId().isBlank()) {
            throw new IllegalArgumentException("候选规则 ruleId 不能为空");
        }
        if (spec.metric() == null || spec.metric().isBlank()) {
            throw new IllegalArgumentException("候选规则 metric 不能为空: " + spec.ruleId());
        }
        if (spec.operator() == null) {
            throw new IllegalArgumentException("候选规则 operator 不能为空: " + spec.ruleId());
        }
        if (!Double.isFinite(spec.threshold())) {
            throw new IllegalArgumentException("候选规则阈值必须为有限数值: " + spec.ruleId());
        }
    }

    /**
     * 对单条候选规则在有序历史点上跑触发/恢复状态机。
     * 与线上语义对齐：缺指标不判定；越限开启告警片段，回到阈值内闭合；
     * 窗口结束仍未恢复的片段 resolvedAt 为 null。
     */
    private static List<ReplayAlert> replayRule(ReplayRuleSpec spec, List<HistoryPoint> points) {
        List<ReplayAlert> episodes = new ArrayList<>();
        if (!spec.enabled()) {
            return episodes;
        }
        Double triggerValue = null;
        long triggeredAt = 0;
        int triggerCount = 0;
        for (HistoryPoint p : points) {
            Double value = p.metrics().get(spec.metric());
            if (value == null) {
                continue; // METRIC_ABSENT：与线上一致，不驱动状态机
            }
            boolean violated = spec.operator().test(value, spec.threshold());
            if (violated) {
                if (triggerValue == null) {
                    triggerValue = value;
                    triggeredAt = p.receivedAt();
                    triggerCount = 1;
                } else {
                    triggerCount++;
                }
            } else if (triggerValue != null) {
                episodes.add(new ReplayAlert(spec.ruleId(), spec.metric(), triggerValue,
                        triggeredAt, p.receivedAt(), value, triggerCount));
                triggerValue = null;
                triggerCount = 0;
            }
        }
        if (triggerValue != null) {
            episodes.add(new ReplayAlert(spec.ruleId(), spec.metric(), triggerValue,
                    triggeredAt, null, null, triggerCount));
        }
        return episodes;
    }

    /**
     * 候选 vs 线上 差异对照。按 ruleId 分组、按时间重叠配对：
     * 未配对的候选片段 → ADDED（多报）；未配对的线上告警 → REMOVED（少报）；
     * 配对成功但触发时刻或持续时长不同 → CHANGED。
     */
    private static List<ReplayDiff> diff(Map<String, ReplayRuleSpec> specsById,
                                         List<ReplayAlert> candidateAlerts,
                                         List<AlertView> liveAlerts) {
        Map<String, List<ReplayAlert>> candidateByRule = new HashMap<>();
        for (ReplayAlert a : candidateAlerts) {
            candidateByRule.computeIfAbsent(a.ruleId(), k -> new ArrayList<>()).add(a);
        }
        Map<String, List<AlertView>> liveByRule = new HashMap<>();
        for (AlertView a : liveAlerts) {
            liveByRule.computeIfAbsent(a.ruleId(), k -> new ArrayList<>()).add(a);
        }

        List<ReplayDiff> diffs = new ArrayList<>();
        for (String ruleId : candidateByRule.keySet()) {
            ReplayRuleSpec spec = specsById.get(ruleId);
            List<AlertView> lives = liveByRule.getOrDefault(ruleId, List.of());
            boolean[] matched = new boolean[lives.size()];
            for (ReplayAlert candidate : candidateByRule.get(ruleId)) {
                int idx = findOverlapping(candidate, lives, matched);
                if (idx < 0) {
                    diffs.add(new ReplayDiff(ReplayDiff.Type.ADDED, ruleId, candidate.metric(),
                            null, null, candidate,
                            "候选规则(" + describe(spec) + ")在 t=" + candidate.triggeredAt()
                                    + " 由输入值 " + candidate.triggerValue() + " 触发，线上生效规则无对应告警（多报）"));
                    continue;
                }
                matched[idx] = true;
                AlertView live = lives.get(idx);
                boolean sameTrigger = live.triggeredAt() == candidate.triggeredAt();
                boolean sameDuration = java.util.Objects.equals(durationOf(live), candidate.durationMillis());
                if (!sameTrigger || !sameDuration) {
                    diffs.add(new ReplayDiff(ReplayDiff.Type.CHANGED, ruleId, candidate.metric(),
                            live.ruleVersion(), live, candidate,
                            "两侧都触发但时序不同：候选(" + describe(spec) + ")触发于 t="
                                    + candidate.triggeredAt() + "（输入值 " + candidate.triggerValue()
                                    + "，持续 " + candidate.durationMillis() + "ms），线上规则 v"
                                    + live.ruleVersion() + " 触发于 t=" + live.triggeredAt()
                                    + "（输入值 " + live.triggerValue() + "，持续 "
                                    + durationOf(live) + "ms）"));
                }
            }
            for (int i = 0; i < lives.size(); i++) {
                if (!matched[i]) {
                    AlertView live = lives.get(i);
                    diffs.add(new ReplayDiff(ReplayDiff.Type.REMOVED, ruleId, live.metric(),
                            live.ruleVersion(), live, null,
                            "线上规则 v" + live.ruleVersion() + " 在 t=" + live.triggeredAt()
                                    + " 由输入值 " + live.triggerValue() + " 触发的告警，候选规则("
                                    + describe(spec) + ")不再触发（少报）"));
                }
            }
        }
        // 线上有告警但候选侧没有任何对应告警片段：候选规则存在但未触发 → 少报；
        // 候选规则集中根本没有该 ruleId → 整体缺失，同样记少报但说明原因
        for (Map.Entry<String, List<AlertView>> e : liveByRule.entrySet()) {
            if (candidateByRule.containsKey(e.getKey())) {
                continue;
            }
            ReplayRuleSpec spec = specsById.get(e.getKey());
            for (AlertView live : e.getValue()) {
                String detail = spec != null
                        ? "线上规则 v" + live.ruleVersion() + " 在 t=" + live.triggeredAt()
                                + " 由输入值 " + live.triggerValue() + " 触发的告警，候选规则("
                                + describe(spec) + ")不再触发（少报）"
                        : "线上规则 v" + live.ruleVersion() + " 在 t=" + live.triggeredAt()
                                + " 由输入值 " + live.triggerValue() + " 触发，但候选规则集中不存在规则 "
                                + live.ruleId() + "（少报）";
                diffs.add(new ReplayDiff(ReplayDiff.Type.REMOVED, live.ruleId(), live.metric(),
                        live.ruleVersion(), live, null, detail));
            }
        }
        diffs.sort(Comparator.comparing(ReplayDiff::ruleId)
                .thenComparing(ReplayDiff::type)
                .thenComparingLong(d -> d.candidateAlert() != null
                        ? d.candidateAlert().triggeredAt() : d.liveAlert().triggeredAt()));
        return diffs;
    }

    /** 在时间上与候选片段重叠且未被配对的线上告警下标；无则 -1。 */
    private static int findOverlapping(ReplayAlert candidate, List<AlertView> lives, boolean[] matched) {
        long cStart = candidate.triggeredAt();
        long cEnd = candidate.resolvedAt() == null ? Long.MAX_VALUE : candidate.resolvedAt();
        for (int i = 0; i < lives.size(); i++) {
            if (matched[i]) {
                continue;
            }
            AlertView live = lives.get(i);
            long lStart = live.triggeredAt();
            long lEnd = live.resolvedAt() == null ? Long.MAX_VALUE : live.resolvedAt();
            if (cStart <= lEnd && lStart <= cEnd) {
                return i;
            }
        }
        return -1;
    }

    private static Long durationOf(AlertView a) {
        return a.resolvedAt() == null ? null : a.resolvedAt() - a.triggeredAt();
    }

    private static String describe(ReplayRuleSpec spec) {
        return spec == null ? "未知候选规则"
                : spec.operator() + " " + spec.threshold();
    }
}
