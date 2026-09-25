package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 期望状态（desired）写入的处理结论。
 */
public enum ShadowUpdateStatus {
    /** 写入成功 */
    OK,
    /** 乐观锁版本冲突：客户端携带的 expectedVersion 已过期 */
    VERSION_CONFLICT,
    /** 设备不存在 */
    UNKNOWN_DEVICE,
    /** 非法状态：空状态或指标值非法（NaN/无穷） */
    INVALID_STATE
}
