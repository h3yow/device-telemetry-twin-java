package com.github.h3yow.devicetelemetrytwinjava.domain;

import java.util.Map;

/**
 * 设备影子的不可变快照，供多入口并发读取。
 *
 * @param deviceId        设备 ID
 * @param reported        最近一次已接受上报的指标
 * @param desired         期望状态（desired）
 * @param reportedVersion 上报状态版本（每次接受 +1，首版为 1）
 * @param desiredVersion  期望状态版本（每次成功更新 +1，从 0 开始表示从未设置）
 * @param lastSeq         最近一次已接受上报的设备序列号
 * @param lastEventTs     最近一次已接受上报的事件时间戳
 * @param updatedAt       快照生成时间（epoch millis）
 */
public record DeviceShadow(String deviceId,
                           Map<String, Double> reported,
                           Map<String, Double> desired,
                           long reportedVersion,
                           long desiredVersion,
                           long lastSeq,
                           long lastEventTs,
                           long updatedAt) {
}
