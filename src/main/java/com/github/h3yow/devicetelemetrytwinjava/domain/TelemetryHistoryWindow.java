package com.github.h3yow.devicetelemetrytwinjava.domain;

import java.util.List;

/**
 * 某台设备一段时间内留存的原始遥测视图（不可变快照），供只读查询与试算使用。
 *
 * @param deviceId      设备 ID
 * @param fromTs        请求窗口起点（事件时间，含）
 * @param toTs          请求窗口终点（事件时间，含）
 * @param points        窗口内按接收顺序（seq）留存的已接受遥测
 * @param coverage      覆盖情况：完整 / 部分被淘汰 / 窗口内无数据
 * @param evictedHighTs 已淘汰数据的最大事件时间（无淘汰为 null）
 * @param oldestTs      当前留存最早事件时间（无数据为 null）
 * @param latestTs      当前留存最晚事件时间（无数据为 null）
 */
public record TelemetryHistoryWindow(String deviceId,
                                     long fromTs,
                                     long toTs,
                                     List<TelemetryReport> points,
                                     TrialCoverage coverage,
                                     Long evictedHighTs,
                                     Long oldestTs,
                                     Long latestTs) {
}
