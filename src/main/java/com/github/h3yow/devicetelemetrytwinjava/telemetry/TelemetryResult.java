package com.github.h3yow.devicetelemetrytwinjava.telemetry;

import com.github.h3yow.devicetelemetrytwinjava.common.IngestionStatus;

/**
 * 遥测接入结论。
 *
 * @param message         原始上报（回显，便于日志核对）
 * @param status          处理结论
 * @param reason          人可读的判定依据
 * @param appliedToShadow 是否实际写入设备影子并驱动告警
 */
public record TelemetryResult(TelemetryMessage message,
                              IngestionStatus status,
                              String reason,
                              boolean appliedToShadow) {
}
