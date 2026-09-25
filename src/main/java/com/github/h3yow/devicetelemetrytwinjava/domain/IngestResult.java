package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 遥测上报处理结果，包含可区分的结论与人类可读的判定依据，绝不静默丢弃。
 *
 * @param status        处理结论
 * @param reason        判定原因/依据
 * @param deviceId      设备 ID（回显）
 * @param seq           上报序列号（回显）
 * @param shadowVersion 处理后影子上报状态版本；未写入时为 -1
 */
public record IngestResult(IngestStatus status,
                           String reason,
                           String deviceId,
                           long seq,
                           long shadowVersion) {

    public static IngestResult of(IngestStatus status, String reason, TelemetryReport r, long shadowVersion) {
        return new IngestResult(status, reason, r.deviceId(), r.seq(), shadowVersion);
    }
}
