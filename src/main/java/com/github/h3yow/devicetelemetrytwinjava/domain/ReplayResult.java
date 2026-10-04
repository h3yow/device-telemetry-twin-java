package com.github.h3yow.devicetelemetrytwinjava.domain;

import java.util.List;

/**
 * 一次规则试算的完整结果。
 *
 * @param deviceId            设备 ID
 * @param fromTs              请求窗口起点（epoch millis，含）
 * @param toTs                请求窗口终点（epoch millis，含）
 * @param coverage            历史覆盖情况（区分“历史已淘汰”与“确实无数据/无告警”）
 * @param earliestRetainedTs  当前留存的最早历史点接收时间；无任何留存时为 null
 * @param pointsReplayed      实际参与回放的历史点数量
 * @param candidateAlerts     候选规则回放产生的告警片段
 * @param liveAlertsInWindow  同一时间窗口内线上生效规则产生的告警快照
 * @param diffs               候选 vs 线上 的差异列表
 */
public record ReplayResult(String deviceId,
                           long fromTs,
                           long toTs,
                           Coverage coverage,
                           Long earliestRetainedTs,
                           int pointsReplayed,
                           List<ReplayAlert> candidateAlerts,
                           List<AlertView> liveAlertsInWindow,
                           List<ReplayDiff> diffs) {

    /**
     * 历史覆盖情况：
     * FULL —— 请求窗口未与已淘汰区域重叠，窗口内的历史完整可查（零告警即“确实没有告警”）；
     * PARTIAL_EVICTED —— 窗口与已淘汰区域重叠（窗口起点不晚于被淘汰点的最大接收时间），
     * 更早的历史已被淘汰，结论仅覆盖仍留存的部分；
     * EMPTY —— 该设备当前没有任何留存历史，且窗口未与已淘汰区域重叠
     * （可能从未上报，或数据远早于窗口且已淘汰）。
     */
    public enum Coverage {
        FULL,
        PARTIAL_EVICTED,
        EMPTY
    }
}
