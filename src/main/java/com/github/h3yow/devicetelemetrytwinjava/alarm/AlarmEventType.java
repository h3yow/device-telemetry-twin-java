package com.github.h3yow.devicetelemetrytwinjava.alarm;

/**
 * 告警事件类型：触发 / 抑制（命中但处于抑制窗口）/ 恢复。
 */
public enum AlarmEventType {
    FIRE,
    SUPPRESSED,
    RECOVER
}
