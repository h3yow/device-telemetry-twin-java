package com.github.h3yow.devicetelemetrytwinjava.shadow;

/**
 * 影子写入入口：区分期望状态下发与设备上报，处理结论可解释。
 */
public enum ShadowUpdateOrigin {
    DESIRED,
    REPORTED
}
