package com.github.h3yow.devicetelemetrytwinjava.alarm;

import java.util.List;

/**
 * 一次遥测对全部适用规则的告警判定结论。
 *
 * @param events 本次判定新产生的事件（含 FIRE / SUPPRESSED / RECOVER）
 */
public record AlarmDecision(List<AlarmEvent> events) {
}
