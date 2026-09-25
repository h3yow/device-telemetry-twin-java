package com.github.h3yow.devicetelemetrytwinjava.web;

import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmEvent;
import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmService;
import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 告警查询 HTTP 入口：历史事件按时间顺序留痕（结合规则 revision 可复现历史判定）。
 */
@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmService alarmService;

    public AlarmController(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    @GetMapping("/devices/{deviceId}/rules/{ruleId}/state")
    public AlarmState state(@PathVariable String deviceId, @PathVariable String ruleId) {
        return alarmService.currentState(deviceId, ruleId).orElse(null);
    }

    @GetMapping("/devices/{deviceId}/rules/{ruleId}/events")
    public List<AlarmEvent> events(@PathVariable String deviceId, @PathVariable String ruleId) {
        return alarmService.replay(deviceId, ruleId);
    }
}
