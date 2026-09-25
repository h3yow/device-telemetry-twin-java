package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmEvent;
import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmEventType;
import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmService;
import com.github.h3yow.devicetelemetrytwinjava.alarm.AlarmStatus;
import com.github.h3yow.devicetelemetrytwinjava.device.DeviceRegistry;
import com.github.h3yow.devicetelemetrytwinjava.pipeline.IngestionPipeline;
import com.github.h3yow.devicetelemetrytwinjava.rule.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.rule.ComparisonOp;
import com.github.h3yow.devicetelemetrytwinjava.rule.RuleService;
import com.github.h3yow.devicetelemetrytwinjava.telemetry.TelemetryMessage;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 场景四：规则版本化——历史事件记录产生时的 revision；
 * 规则阈值变更后，仍可用历史 revision 对同一份历史数据复现旧判定。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RuleChangeReproducibilityTest {

    private static final Logger log = LoggerFactory.getLogger(RuleChangeReproducibilityTest.class);
    private static final String DEVICE = "device-rule-change";
    private static final String RULE = "temp-threshold";

    @Autowired
    private IngestionPipeline pipeline;
    @Autowired
    private DeviceRegistry registry;
    @Autowired
    private RuleService ruleService;
    @Autowired
    private AlarmService alarmService;

    @Test
    void historical_decisions_are_reproducible_after_rule_update() {
        registry.register(DEVICE, java.util.Set.of("temp"));
        // rev=1: 阈值 90
        ruleService.register(RULE, DEVICE, "temp", ComparisonOp.GT, 90, 0);

        long t = 9_000_000;
        double[] temps = {80, 95, 97, 85};
        List<TelemetryMessage> history = new ArrayList<>();
        for (int i = 0; i < temps.length; i++) {
            TelemetryMessage m = new TelemetryMessage(
                    DEVICE, Map.of("temp", temps[i]), t + i * 10_000L,
                    i + 1, UUID.randomUUID().toString());
            history.add(m);
            log.info("[历史输入] seq={} temp={}", m.seq(), temps[i]);
            pipeline.ingest(m);
        }

        // rev=1 下事件：FIRE(95) -> FIRE(97, 超过 0 抑制窗口) -> RECOVER(85)
        List<AlarmEvent> underRev1 = alarmService.replay(DEVICE, RULE);
        log.info("[rev=1 实判事件] {}", underRev1.stream()
                .map(e -> e.type() + "(rev=" + e.revision() + ")").toList());
        for (AlarmEvent e : underRev1) {
            assertEquals(1, e.revision(), "历史事件必须固化产生时的规则 revision");
        }
        assertEquals(List.of(AlarmEventType.FIRE, AlarmEventType.FIRE, AlarmEventType.RECOVER),
                underRev1.stream().map(AlarmEvent::type).toList());

        // 规则变更：阈值 90 -> 96，产生 rev=2
        AlertRule rev2 = ruleService.update(RULE, null, 96.0, (Long) null, (Boolean) null);
        assertEquals(2, rev2.revision());
        log.info("[规则变更] {} rev=2 threshold={}", RULE, rev2.threshold());

        // 变更时刻告警处于 RECOVERED；rev=2 下 95/97 中只有 97 越限。
        // 用 rev=2 对同一份历史数据复算：FIRE(97) -> RECOVER(85)
        List<AlarmEventType> recomputedUnderRev2 =
                alarmService.reevaluate(RULE, 2, history);
        log.info("[rev=2 历史复算] {}", recomputedUnderRev2);
        assertEquals(List.of(AlarmEventType.FIRE, AlarmEventType.RECOVER),
                recomputedUnderRev2);

        // 用 rev=1 复算必须与当时实判完全一致（历史判定可复现）
        List<AlarmEventType> recomputedUnderRev1 =
                alarmService.reevaluate(RULE, 1, history);
        log.info("[rev=1 历史复算] {}", recomputedUnderRev1);
        assertEquals(underRev1.stream().map(AlarmEvent::type).toList(),
                recomputedUnderRev1);
        assertNotEquals(recomputedUnderRev1, recomputedUnderRev2);

        // 新数据按 rev=2 判定，且事件上标记 revision=2，旧事件保持 revision=1 不变
        TelemetryMessage postChange = new TelemetryMessage(
                DEVICE, Map.of("temp", 95.5), t + 100_000, 5, UUID.randomUUID().toString());
        log.info("[变更后输入] temp=95.5（rev=2 阈值 96，不越限）");
        pipeline.ingest(postChange);
        assertEquals(AlarmStatus.RECOVERED,
                alarmService.currentState(DEVICE, RULE).orElseThrow().status());
        assertEquals(1, alarmService.replay(DEVICE, RULE).get(0).revision());
        assertEquals(1, alarmService.replay(DEVICE, RULE).get(1).revision());
        log.info("[一致性核对] 旧事件 revision 保持 1，规则变更未篡改历史");
    }

    @Test
    void disabled_rule_produces_no_new_events_but_history_remains() {
        registry.register(DEVICE, java.util.Set.of("temp"));
        ruleService.register(RULE, DEVICE, "temp", ComparisonOp.GT, 90, 0);

        long t = 10_000_000;
        pipeline.ingest(new TelemetryMessage(DEVICE, Map.of("temp", 95.0), t, 1,
                UUID.randomUUID().toString()));
        assertEquals(1, alarmService.replay(DEVICE, RULE).size());

        // 停用规则 -> rev=2
        AlertRule disabled = ruleService.update(RULE, null, (Double) null, (Long) null, false);
        assertEquals(2, disabled.revision());
        log.info("[规则停用] {} rev=2 enabled=false", RULE);

        // 停用后继续越限也不再产生事件
        pipeline.ingest(new TelemetryMessage(DEVICE, Map.of("temp", 99.0), t + 1_000, 2,
                UUID.randomUUID().toString()));
        assertEquals(1, alarmService.replay(DEVICE, RULE).size());
        log.info("[停用后核对] 历史事件保留 {} 条，无新增", alarmService.replay(DEVICE, RULE).size());
    }
}
