package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 遥测上报的处理结论。
 * <ul>
 *   <li>{@link #ACCEPTED} 正常接收并写入影子；</li>
 *   <li>{@link #DEGRADED} 接收但降级（例如指标越界被清洗），影子已写入，需人工关注；</li>
 *   <li>{@link #DUPLICATE} 完全重复上报，幂等忽略；</li>
 *   <li>{@link #STALE} 迟到/乱序的过期数据，不得覆盖较新状态；</li>
 *   <li>{@link #REJECTED} 非法上报（设备不存在 / 时间戳来自未来 / 载荷为空），明确拒绝。</li>
 * </ul>
 */
public enum IngestStatus {
    ACCEPTED,
    DEGRADED,
    DUPLICATE,
    STALE,
    REJECTED
}
