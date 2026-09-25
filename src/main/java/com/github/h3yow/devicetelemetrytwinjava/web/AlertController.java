package com.github.h3yow.devicetelemetrytwinjava.web;

import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.AlertView;
import com.github.h3yow.devicetelemetrytwinjava.domain.EvaluationRecord;
import com.github.h3yow.devicetelemetrytwinjava.service.AlertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 告警入口：规则管理（含版本化变更）、抑制、告警与判定历史查询。
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * 创建/变更规则（相同 ruleId 每次下发 ruleVersion+1）。
     * <pre>{"ruleId":"rule-temp-high","deviceId":"sim-thermo-001",
     * "metric":"temperature","operator":"GT","threshold":30.0,"enabled":true}</pre>
     */
    @PutMapping("/rules")
    public AlertRule putRule(@RequestBody RuleRequest req) {
        return alertService.putRule(req.ruleId(), req.deviceId(), req.metric(),
                AlertRule.Operator.valueOf(req.operator()), req.threshold(),
                req.enabled() == null || req.enabled());
    }

    @GetMapping("/rules/{deviceId}")
    public List<AlertRule> rules(@PathVariable String deviceId) {
        return alertService.rulesForDevice(deviceId);
    }

    /** 对某规则当前活动告警施加抑制窗口（毫秒）。 */
    @PostMapping("/rules/{ruleId}/suppress")
    public AlertService.SuppressionResult suppress(@PathVariable String ruleId,
                                                   @RequestParam long durationMillis) {
        return alertService.suppress(ruleId, durationMillis);
    }

    @GetMapping("/devices/{deviceId}")
    public List<AlertView> alerts(@PathVariable String deviceId) {
        return alertService.alertsForDevice(deviceId);
    }

    @GetMapping("/history/devices/{deviceId}")
    public List<EvaluationRecord> deviceHistory(@PathVariable String deviceId) {
        return alertService.historyForDevice(deviceId);
    }

    @GetMapping("/history/rules/{ruleId}")
    public List<EvaluationRecord> ruleHistory(@PathVariable String ruleId) {
        return alertService.historyForRule(ruleId);
    }

    /** 规则请求体。 */
    public record RuleRequest(String ruleId,
                              String deviceId,
                              String metric,
                              String operator,
                              double threshold,
                              Boolean enabled) {
    }
}
