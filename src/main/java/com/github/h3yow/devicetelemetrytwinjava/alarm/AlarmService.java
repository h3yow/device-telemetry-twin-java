package com.github.h3yow.devicetelemetrytwinjava.alarm;

import com.github.h3yow.devicetelemetrytwinjava.rule.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.rule.RuleService;
import com.github.h3yow.devicetelemetrytwinjava.telemetry.TelemetryMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警服务。由 pipeline 在持有对应设备锁的情况下调用，因此同一设备的判定天然串行，
 * 并发上报不会产生重复告警或漏报。
 *
 * <p>每个 (device, rule) 维护一份当前状态；每次遥测驱动一次判定：
 * <ul>
 *   <li>条件成立且无 ACTIVE：FIRE（进入 ACTIVE）；</li>
 *   <li>条件成立且 ACTIVE：若距上次 FIRE 在抑制窗口内 -> SUPPRESSED（不重复 FIRE），
 *       超过窗口则再次 FIRE（周期性提醒，每次都是新事件）；</li>
 *   <li>条件不成立且存在 ACTIVE：RECOVER（转回 RECOVERED）。</li>
 * </ul>
 * 事件不可变并记录判定时规则 revision；规则变更后可用 replay 依据历史指标重放复现判定。
 */
@Service
public class AlarmService {

    private final RuleService ruleService;

    /** key = deviceId + "|" + ruleId */
    private final ConcurrentHashMap<String, MutableAlarm> alarms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<AlarmEvent>> eventLog = new ConcurrentHashMap<>();
    private final AtomicLong eventSequence = new AtomicLong();

    public AlarmService(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    public AlarmDecision evaluate(TelemetryMessage message) {
        List<AlarmEvent> produced = new ArrayList<>();
        for (AlertRule rule : ruleService.rulesForDevice(message.deviceId())) {
            Double value = message.metrics().get(rule.metric());
            if (value == null || !rule.enabled()) {
                // 本次上报不含该规则指标，或规则已停用：不产生任何判定（保持现有状态）
                continue;
            }
            boolean breached = rule.op().test(value, rule.threshold());
            MutableAlarm alarm = alarms.computeIfAbsent(
                    key(message.deviceId(), rule.id()),
                    k -> new MutableAlarm(message.deviceId(), rule.id()));

            AlarmEventType type;
            String basis;
            long now = System.currentTimeMillis();
            if (breached) {
                if (!alarm.active) {
                    type = AlarmEventType.FIRE;
                    basis = String.format(
                            "%s=%s %s %s 成立，此前无活动告警 -> FIRE（规则 rev=%d）",
                            rule.metric(), value, rule.op(), rule.threshold(), rule.revision());
                    alarm.active = true;
                    alarm.lastFiredAt = message.timestamp();
                } else if (message.timestamp() - alarm.lastFiredAt <= rule.suppressMillis()) {
                    type = AlarmEventType.SUPPRESSED;
                    basis = String.format(
                            "%s=%s %s %s 仍成立，但距上次触发 %dms <= 抑制窗口 %dms -> SUPPRESSED（规则 rev=%d）",
                            rule.metric(), value, rule.op(), rule.threshold(),
                            message.timestamp() - alarm.lastFiredAt,
                            rule.suppressMillis(), rule.revision());
                } else {
                    type = AlarmEventType.FIRE;
                    basis = String.format(
                            "%s=%s %s %s 持续成立且超过抑制窗口 %dms -> 再次 FIRE（规则 rev=%d）",
                            rule.metric(), value, rule.op(), rule.threshold(),
                            rule.suppressMillis(), rule.revision());
                    alarm.lastFiredAt = message.timestamp();
                }
            } else {
                if (alarm.active) {
                    type = AlarmEventType.RECOVER;
                    basis = String.format(
                            "%s=%s 不再满足 %s %s -> RECOVER（规则 rev=%d）",
                            rule.metric(), value, rule.op(), rule.threshold(), rule.revision());
                    alarm.active = false;
                } else {
                    // 未触发且条件不成立：无状态迁移，不留事件，避免噪声
                    continue;
                }
            }

            long eventId = eventSequence.incrementAndGet();
            if (type == AlarmEventType.FIRE) {
                alarm.lastFiredEventId = eventId;
            }
            AlarmEvent event = new AlarmEvent(
                    eventId,
                    message.deviceId(), rule.id(), rule.revision(), type,
                    Map.copyOf(message.metrics()), message.seq(), message.timestamp(),
                    now, basis);
            alarm.currentRevision = rule.revision();
            alarm.lastEvaluatedSeq = message.seq();
            alarm.lastEvaluatedAt = message.timestamp();
            produced.add(event);
            eventLog.computeIfAbsent(key(message.deviceId(), rule.id()), k -> new ArrayList<>())
                    .add(event);
        }
        return new AlarmDecision(List.copyOf(produced));
    }

    public List<AlarmEvent> replay(String deviceId, String ruleId) {
        return List.copyOf(eventLog.getOrDefault(key(deviceId, ruleId), List.of()));
    }

    /**
     * 用指定 revision 的历史规则，对给定的历史遥测序列做离线复算（不改变任何当前状态）。
     * 规则 revision 不可变、历史事件也记录了当时 revision，因此规则变更后历史判定可被重现。
     *
     * @return 复算出的事件类型序列（FIRE / SUPPRESSED / RECOVER）
     */
    public List<AlarmEventType> reevaluate(String ruleId, int revision,
                                          List<TelemetryMessage> history) {
        AlertRule rule = ruleService.getRevision(ruleId, revision)
                .orElseThrow(() -> new IllegalArgumentException(
                        "规则版本不存在: " + ruleId + " rev=" + revision));
        List<AlarmEventType> result = new ArrayList<>();
        boolean active = false;
        long lastFiredAt = Long.MIN_VALUE;
        for (TelemetryMessage m : history) {
            Double value = m.metrics().get(rule.metric());
            if (value == null) {
                continue;
            }
            boolean breached = rule.op().test(value, rule.threshold());
            if (breached) {
                if (!active) {
                    result.add(AlarmEventType.FIRE);
                    active = true;
                    lastFiredAt = m.timestamp();
                } else if (m.timestamp() - lastFiredAt <= rule.suppressMillis()) {
                    result.add(AlarmEventType.SUPPRESSED);
                } else {
                    result.add(AlarmEventType.FIRE);
                    lastFiredAt = m.timestamp();
                }
            } else if (active) {
                result.add(AlarmEventType.RECOVER);
                active = false;
            }
        }
        return result;
    }

    public Optional<AlarmState> currentState(String deviceId, String ruleId) {
        MutableAlarm a = alarms.get(key(deviceId, ruleId));
        if (a == null) {
            return Optional.empty();
        }
        return Optional.of(new AlarmState(deviceId, ruleId,
                a.active ? AlarmStatus.ACTIVE : AlarmStatus.RECOVERED,
                a.currentRevision, a.lastFiredEventId, a.lastFiredAt,
                a.lastEvaluatedSeq, a.lastEvaluatedAt));
    }

    private static String key(String deviceId, String ruleId) {
        return deviceId + "|" + ruleId;
    }

    private static final class MutableAlarm {
        final String deviceId;
        final String ruleId;
        volatile boolean active = false;
        volatile int currentRevision = 0;
        volatile long lastFiredEventId = -1;
        volatile long lastFiredAt = Long.MIN_VALUE;
        volatile long lastEvaluatedSeq = -1;
        volatile long lastEvaluatedAt = Long.MIN_VALUE;

        MutableAlarm(String deviceId, String ruleId) {
            this.deviceId = deviceId;
            this.ruleId = ruleId;
        }
    }
}
