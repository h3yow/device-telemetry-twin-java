package com.github.h3yow.devicetelemetrytwinjava.service;

import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.EpisodeDiff;
import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryHistoryWindow;
import com.github.h3yow.devicetelemetrytwinjava.domain.TrialEpisode;
import com.github.h3yow.devicetelemetrytwinjava.domain.TrialRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.TrialRun;
import com.github.h3yow.devicetelemetrytwinjava.domain.WhatIfResult;
import com.github.h3yow.devicetelemetrytwinjava.store.AlertStore;
import com.github.h3yow.devicetelemetrytwinjava.store.DeviceRegistry;
import com.github.h3yow.devicetelemetrytwinjava.store.TelemetryHistoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则试算服务：只读历史 + 候选规则，确定性回放并与当前生效规则对照。
 *
 * <p>隔离保证：
 * <ul>
 *   <li>只从 {@link TelemetryHistoryStore} 取历史快照、从 {@link AlertStore} 读规则快照，
 *       全程<b>不写</b>任何存储；不修改线上影子、告警、判定历史，也不影响正在进行的判定；</li>
 *   <li>不获取线上设备锁，试算与线上并发上报互不阻塞；</li>
 *   <li>不使用当前时钟：候选规则版本、结果排序均由输入确定，同一批历史 + 同一份候选规则
 *       重复试算结果逐字节一致。</li>
 * </ul>
 */
@Service
public class TrialService {

    private static final Logger log = LoggerFactory.getLogger(TrialService.class);

    private final TelemetryHistoryStore historyStore;
    private final AlertStore alertStore;
    private final DeviceRegistry deviceRegistry;

    public TrialService(TelemetryHistoryStore historyStore,
                        AlertStore alertStore,
                        DeviceRegistry deviceRegistry) {
        this.historyStore = historyStore;
        this.alertStore = alertStore;
        this.deviceRegistry = deviceRegistry;
    }

    /** 查询设备某段时间留存的原始遥测（只读）。 */
    public TelemetryHistoryWindow history(String deviceId, long fromTs, long toTs) {
        validateDevice(deviceId);
        return historyStore.window(deviceId, fromTs, toTs);
    }

    /**
     * 对指定设备、时间窗口、候选规则组执行试算，并与同一窗口下当前生效规则的回放对照。
     *
     * @param deviceId   设备 ID（必须已注册）
     * @param fromTs     窗口起点（事件时间，含）
     * @param toTs       窗口终点（事件时间，含）
     * @param candidates 候选规则组；ruleId 与当前规则相同视为修改，否则视为新增。
     *                   只携带该设备的规则；候选组里没有的“当前规则”按沿用当前规则处理
     *                   （即候选组是“完整候选规则集”，未列出即删除）
     */
    public WhatIfResult runWhatIf(String deviceId, long fromTs, long toTs, List<TrialRule> candidates) {
        validateDevice(deviceId);
        if (fromTs > toTs) {
            throw new IllegalArgumentException("时间窗口非法: fromTs=" + fromTs + " > toTs=" + toTs);
        }
        List<TrialRule> safeCandidates = validateCandidates(deviceId, candidates);

        // 1) 历史快照（只读）
        TelemetryHistoryWindow window = historyStore.window(deviceId, fromTs, toTs);
        // 历史按 seq 再稳定排序一次（留存本就有序，排序仅为消除任何歧义，保证可复现）
        List<com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryReport> points =
                new ArrayList<>(window.points());
        points.sort(Comparator.comparingLong(
                com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryReport::seq));

        // 2) 当前生效规则快照（同一设备、按 ruleId 排序），作为 baseline
        List<AlertRule> baselineRules = new ArrayList<>(alertStore.rulesForDevice(deviceId));
        baselineRules.sort(Comparator.comparing(AlertRule::ruleId));

        // 3) 候选规则映射为试算用的不可变规则对象，版本号确定地分配：
        //    已有 ruleId -> 当前版本 + 1；新增 ruleId -> 0（语义上的“候选初版”）
        Map<String, AlertRule> baselineById = new LinkedHashMap<>();
        for (AlertRule r : baselineRules) {
            baselineById.put(r.ruleId(), r);
        }
        List<AlertRule> candidateRules = new ArrayList<>();
        for (TrialRule c : safeCandidates) {
            AlertRule base = baselineById.get(c.ruleId());
            int version = base == null ? 0 : base.ruleVersion() + 1;
            candidateRules.add(c.toAlertRule(version, 0L));
        }
        candidateRules.sort(Comparator.comparing(AlertRule::ruleId));

        // 4) 两侧确定性回放
        List<TrialEpisode> baselineEpisodes = TrialEngine.replay(baselineRules, points);
        List<TrialEpisode> candidateEpisodes = TrialEngine.replay(candidateRules, points);

        TrialRun baseline = new TrialRun("baseline", baselineRules, points.size(), baselineEpisodes);
        TrialRun candidate = new TrialRun("candidate", candidateRules, points.size(), candidateEpisodes);

        // 5) 差异对照
        List<EpisodeDiff> diffs = diffEpisodes(baselineRules, candidateRules,
                baselineEpisodes, candidateEpisodes);

        log.info("试算 device={} window=[{},{}] points={} coverage={} baseline段={} candidate段={} 差异={}",
                deviceId, fromTs, toTs, points.size(), window.coverage(),
                baselineEpisodes.size(), candidateEpisodes.size(), diffs.size());

        return new WhatIfResult(deviceId, fromTs, toTs, window.coverage(),
                window.evictedHighTs(), window.oldestTs(), window.latestTs(),
                baseline, candidate, diffs);
    }

    private void validateDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("设备 ID 不能为空");
        }
        if (!deviceRegistry.exists(deviceId)) {
            throw new IllegalArgumentException("设备不存在: " + deviceId);
        }
    }

    private List<TrialRule> validateCandidates(String deviceId, List<TrialRule> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("候选规则组不能为空");
        }
        List<TrialRule> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (TrialRule c : candidates) {
            if (c == null) {
                throw new IllegalArgumentException("候选规则不能为 null");
            }
            if (!deviceId.equals(c.deviceId())) {
                throw new IllegalArgumentException(
                        "候选规则 " + c.ruleId() + " 的 deviceId 与试算设备不一致: " + c.deviceId());
            }
            if (c.ruleId() == null || c.ruleId().isBlank()) {
                throw new IllegalArgumentException("候选规则 ruleId 不能为空");
            }
            if (c.metric() == null || c.metric().isBlank()) {
                throw new IllegalArgumentException("候选规则 " + c.ruleId() + " 的指标名不能为空");
            }
            if (c.operator() == null) {
                throw new IllegalArgumentException("候选规则 " + c.ruleId() + " 的算子不能为空");
            }
            if (!Double.isFinite(c.threshold())) {
                throw new IllegalArgumentException(
                        "候选规则 " + c.ruleId() + " 的阈值必须为有限数值");
            }
            if (!seen.add(c.ruleId())) {
                throw new IllegalArgumentException("候选规则组中 ruleId 重复: " + c.ruleId());
            }
            list.add(c);
        }
        list.sort(Comparator.comparing(TrialRule::ruleId));
        return list;
    }

    /**
     * 按规则分组，将两侧告警段配对后产出差异。配对采用确定性的贪心：
     * 两侧各自按触发时刻排序，依次为候选段选择时间区间重叠的、尚未配对的最早基线段；
     * 未配上的候选段 = NEW；未配上的基线段 = REMOVED；配上但触发时刻/恢复时刻/
     * 持续时长不同 = CHANGED，完全相同不报差异。
     */
    private List<EpisodeDiff> diffEpisodes(List<AlertRule> baselineRules,
                                           List<AlertRule> candidateRules,
                                           List<TrialEpisode> baselineEpisodes,
                                           List<TrialEpisode> candidateEpisodes) {
        Map<String, AlertRule> baseRuleById = new LinkedHashMap<>();
        for (AlertRule r : baselineRules) {
            baseRuleById.put(r.ruleId(), r);
        }
        Map<String, AlertRule> candRuleById = new LinkedHashMap<>();
        for (AlertRule r : candidateRules) {
            candRuleById.put(r.ruleId(), r);
        }

        // 两侧规则的并集（按 ruleId 排序），保证“删除规则/新增规则”也能产出差异
        java.util.TreeSet<String> ruleIds = new java.util.TreeSet<>();
        ruleIds.addAll(baseRuleById.keySet());
        ruleIds.addAll(candRuleById.keySet());

        List<EpisodeDiff> diffs = new ArrayList<>();
        for (String ruleId : ruleIds) {
            List<TrialEpisode> bList = episodesOf(baselineEpisodes, ruleId);
            List<TrialEpisode> cList = episodesOf(candidateEpisodes, ruleId);
            boolean[] bMatched = new boolean[bList.size()];
            boolean[] cMatched = new boolean[cList.size()];

            // 贪心配对：为每个候选段找最早的、时间区间重叠的未配对基线段
            for (int ci = 0; ci < cList.size(); ci++) {
                TrialEpisode c = cList.get(ci);
                int best = -1;
                for (int bi = 0; bi < bList.size(); bi++) {
                    if (bMatched[bi]) {
                        continue;
                    }
                    if (overlaps(bList.get(bi), c)) {
                        best = bi;
                        break; // bList 已按 triggeredAt 排序，取第一个即可
                    }
                }
                if (best >= 0) {
                    bMatched[best] = true;
                    cMatched[ci] = true;
                    TrialEpisode b = bList.get(best);
                    if (!sameTiming(b, c)) {
                        diffs.add(new EpisodeDiff(EpisodeDiff.Type.CHANGED, ruleId, c.metric(),
                                b.ruleVersion(), c.ruleVersion(), b, c, describeChanged(b, c)));
                    }
                }
            }

            Integer bVer = baseRuleById.containsKey(ruleId) ? baseRuleById.get(ruleId).ruleVersion() : null;
            Integer cVer = candRuleById.containsKey(ruleId) ? candRuleById.get(ruleId).ruleVersion() : null;

            for (int ci = 0; ci < cList.size(); ci++) {
                if (cMatched[ci]) {
                    continue;
                }
                TrialEpisode c = cList.get(ci);
                // 基线规则仍在（只是原先不越限）时带上基线版本；规则为新增时 bVer 为 null
                diffs.add(new EpisodeDiff(EpisodeDiff.Type.NEW, ruleId, c.metric(),
                        bVer, c.ruleVersion(), null, c,
                        "新增告警段: 触发于 " + c.triggeredAt() + " 值=" + c.triggerValue()
                                + (c.resolvedAt() == null ? "（窗口结束仍未恢复）"
                                : "，恢复于 " + c.resolvedAt())));
            }
            for (int bi = 0; bi < bList.size(); bi++) {
                if (bMatched[bi]) {
                    continue;
                }
                TrialEpisode b = bList.get(bi);
                // 候选规则仍在（只是不再越限）时带上候选版本；候选规则被删除时 cVer 为 null
                diffs.add(new EpisodeDiff(EpisodeDiff.Type.REMOVED, ruleId, b.metric(),
                        b.ruleVersion(), cVer, b, null,
                        "取消的告警段: 原触发于 " + b.triggeredAt() + " 值=" + b.triggerValue()
                                + (b.resolvedAt() == null ? "（窗口结束仍未恢复）"
                                : "，原恢复于 " + b.resolvedAt())));
            }
        }

        diffs.sort(Comparator.comparing(EpisodeDiff::ruleId)
                .thenComparing(d -> d.type().name())
                .thenComparing(d -> d.candidateEpisode() != null ? d.candidateEpisode().triggeredAt()
                        : d.baselineEpisode().triggeredAt()));
        return diffs;
    }

    private static List<TrialEpisode> episodesOf(List<TrialEpisode> episodes, String ruleId) {
        return episodes.stream().filter(e -> e.ruleId().equals(ruleId))
                .sorted(Comparator.comparingLong(TrialEpisode::triggeredAt)
                        .thenComparingInt(TrialEpisode::seq))
                .toList();
    }

    /** 两个告警段的时间区间是否重叠（未恢复段视为延伸到 Long.MAX_VALUE）。 */
    private static boolean overlaps(TrialEpisode a, TrialEpisode b) {
        long aEnd = a.resolvedAt() == null ? Long.MAX_VALUE : a.resolvedAt();
        long bEnd = b.resolvedAt() == null ? Long.MAX_VALUE : b.resolvedAt();
        return a.triggeredAt() <= bEnd && b.triggeredAt() <= aEnd;
    }

    /** 触发时刻与恢复时刻（含持续时长）完全一致才算“无差异”。 */
    private static boolean sameTiming(TrialEpisode a, TrialEpisode b) {
        return a.triggeredAt() == b.triggeredAt()
                && java.util.Objects.equals(a.resolvedAt(), b.resolvedAt());
    }

    private static String describeChanged(TrialEpisode b, TrialEpisode c) {
        StringBuilder sb = new StringBuilder();
        sb.append("触发时刻 ").append(b.triggeredAt()).append(" -> ").append(c.triggeredAt());
        long trigDelta = c.triggeredAt() - b.triggeredAt();
        sb.append("（差 ").append(trigDelta).append("ms）");
        if (b.resolvedAt() == null || c.resolvedAt() == null) {
            sb.append("；恢复情况: ").append(b.resolvedAt() == null ? "基线未恢复" : "基线恢复于 " + b.resolvedAt())
                    .append(" / ").append(c.resolvedAt() == null ? "候选未恢复" : "候选恢复于 " + c.resolvedAt());
        } else {
            sb.append("；持续时长 ").append(b.durationMillis()).append("ms -> ")
                    .append(c.durationMillis()).append("ms");
        }
        return sb.toString();
    }
}
