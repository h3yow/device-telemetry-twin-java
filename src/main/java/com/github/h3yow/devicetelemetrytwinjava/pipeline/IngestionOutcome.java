package com.github.h3yow.devicetelemetrytwinjava.pipeline;

import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmEvent;
import com.github.h3yow.devicetelemetrytwinjava.telemetry.TelemetryResult;

import java.util.List;

/**
 * 完整接入链路结论：接入判定 + 本次驱动出的告警事件。
 */
public record IngestionOutcome(TelemetryResult ingestion, List<AlarmEvent> alarmEvents) {
}
