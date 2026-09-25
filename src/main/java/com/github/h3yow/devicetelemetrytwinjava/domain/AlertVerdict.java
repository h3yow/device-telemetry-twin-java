package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 单次遥测到达后，针对某条规则的告警判定结论，用于在同一状态机上自洽地表达
 * 触发、抑制、恢复与重复抑制：
 * <ul>
 *   <li>{@link #TRIGGERED} 首次越限，产生新告警；</li>
 *   <li>{@link #STILL_ACTIVE} 告警持续越限（幂等，不重复产生告警）；</li>
 *   <li>{@link #SUPPRESSED} 处于抑制窗口内，即使越限也不升级；</li>
 *   <li>{@link #RESOLVED} 已回到阈值内，告警恢复；</li>
 *   <li>{@link #STILL_RESOLVED} 阈值内且无活动告警，无动作；</li>
 *   <li>{@link #RULE_DISABLED} 规则已停用，不驱动状态机、不产生告警，但仍留判定记录；</li>
 *   <li>{@link #METRIC_ABSENT} 本次上报未携带该指标，不判定。</li>
 * </ul>
 */
public enum AlertVerdict {
    TRIGGERED,
    STILL_ACTIVE,
    SUPPRESSED,
    RESOLVED,
    STILL_RESOLVED,
    RULE_DISABLED,
    METRIC_ABSENT
}
