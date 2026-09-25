package com.github.h3yow.devicetelemetrytwinjava.web;

import com.github.h3yow.devicetelemetrytwinjava.rule.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.rule.ComparisonOp;
import com.github.h3yow.devicetelemetrytwinjava.rule.RuleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警规则 HTTP 入口。每次新增/修改都产生新 revision，旧 revision 永久保留。
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleService ruleService;

    public RuleController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    @PostMapping
    public AlertRule register(@RequestBody RuleRequest request) {
        return ruleService.register(request.id(), request.deviceId(), request.metric(),
                request.op(), request.threshold(),
                request.suppressMillis() == null ? 0L : request.suppressMillis());
    }

    @PutMapping("/{ruleId}")
    public AlertRule update(@PathVariable String ruleId,
                            @RequestParam(required = false) ComparisonOp op,
                            @RequestParam(required = false) Double threshold,
                            @RequestParam(required = false) Long suppressMillis,
                            @RequestParam(required = false) Boolean enabled) {
        return ruleService.update(ruleId, op, threshold, suppressMillis, enabled);
    }

    @GetMapping("/{ruleId}")
    public AlertRule current(@PathVariable String ruleId) {
        return ruleService.current(ruleId).orElseThrow();
    }

    @GetMapping("/{ruleId}/revisions/{revision}")
    public AlertRule revision(@PathVariable String ruleId, @PathVariable int revision) {
        return ruleService.getRevision(ruleId, revision).orElseThrow();
    }

    public record RuleRequest(String id, String deviceId, String metric,
                              ComparisonOp op, double threshold, Long suppressMillis) {
    }
}
