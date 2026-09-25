package com.github.h3yow.devicetelemetrytwinjava.alarm;

/**
 * 告警生命周期状态。
 * ACTIVE  : 条件成立，告警处于触发态（抑制窗口内的重复命中不产生新事件）
 * RECOVERED: 条件不再成立，最近一次 ACTIVE 已恢复
 */
public enum AlarmStatus {
    ACTIVE,
    RECOVERED
}
