package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 告警生命周期状态：ACTIVE（告警中）/ SUPPRESSED（抑制中）/ RESOLVED（已恢复）。
 */
public enum AlertState {
    ACTIVE,
    SUPPRESSED,
    RESOLVED
}
