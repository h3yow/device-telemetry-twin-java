package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 期望状态写入结果。
 *
 * @param status        处理结论
 * @param reason        判定依据
 * @param deviceId      设备 ID
 * @param desiredVersion 写入成功后的新版本；失败时为当前版本（便于客户端重试）
 */
public record ShadowUpdateResult(ShadowUpdateStatus status,
                                 String reason,
                                 String deviceId,
                                 long desiredVersion) {
}
