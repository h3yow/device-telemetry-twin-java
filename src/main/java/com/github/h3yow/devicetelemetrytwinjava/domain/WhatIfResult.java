package com.github.h3yow.devicetelemetrytwinjava.domain;

import java.util.List;

/**
 * 一次规则试算的完整结果：候选回放、当前生效规则回放、两侧差异与覆盖情况。
 * 结果完全由输入（历史窗口 + 候选规则）决定，不读取也不改动任何线上状态。
 *
 * @param deviceId       设备 ID
 * @param fromTs         请求窗口起点（事件时间，含）
 * @param toTs           请求窗口终点（事件时间，含）
 * @param coverage       历史覆盖情况
 * @param evictedHighTs  该设备已被淘汰数据的最大事件时间（无淘汰为 null），用于说明淘汰边界
 * @param oldestTs       当前留存的最早事件时间（无数据为 null）
 * @param latestTs       当前留存的最晚事件时间（无数据为 null）
 * @param baseline       当前生效规则在同一窗口的回放
 * @param candidate      候选规则在同一窗口的回放
 * @param diffs          差异列表（NEW/REMOVED/CHANGED，已排序）
 */
public record WhatIfResult(String deviceId,
                           long fromTs,
                           long toTs,
                           TrialCoverage coverage,
                           Long evictedHighTs,
                           Long oldestTs,
                           Long latestTs,
                           TrialRun baseline,
                           TrialRun candidate,
                           List<EpisodeDiff> diffs) {
}
