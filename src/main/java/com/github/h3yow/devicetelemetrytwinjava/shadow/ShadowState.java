package com.github.h3yow.devicetelemetrytwinjava.shadow;

import java.util.Map;

/**
 * 设备影子状态快照。
 *
 * @param deviceId          设备标识
 * @param version           影子版本号（每次期望/上报写入成功后单调递增）
 * @param desired           期望状态
 * @param desiredVersion    期望状态版本（用于乐观锁冲突检测）
 * @param reported          上报状态（最近一次有效遥测的指标）
 * @param reportedVersion   上报状态版本（当前已接受的最大 seq）
 * @param updatedAt         最近更新时间（墙钟）
 */
public record ShadowState(String deviceId,
                          long version,
                          Map<String, Double> desired,
                          long desiredVersion,
                          Map<String, Double> reported,
                          long reportedVersion,
                          long updatedAt) {
}
