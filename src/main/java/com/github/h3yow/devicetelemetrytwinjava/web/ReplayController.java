package com.github.h3yow.devicetelemetrytwinjava.web;

import com.github.h3yow.devicetelemetrytwinjava.domain.AlertRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.ReplayResult;
import com.github.h3yow.devicetelemetrytwinjava.domain.ReplayRuleSpec;
import com.github.h3yow.devicetelemetrytwinjava.service.ReplayService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 规则试算入口：指定设备 + 时间窗口 + 候选规则，对留存历史重跑判定并与线上结果对照。
 */
@RestController
@RequestMapping("/api/replay")
public class ReplayController {

    private final ReplayService replayService;

    public ReplayController(ReplayService replayService) {
        this.replayService = replayService;
    }

    /**
     * 试算请求。
     * <pre>{"deviceId":"sim-thermo-001","fromTs":1700000000000,"toTs":1700003600000,
     * "rules":[{"ruleId":"rule-temp-high","metric":"temperature","operator":"GT",
     * "threshold":28.0,"enabled":true}]}</pre>
     */
    @PostMapping
    public ReplayResult replay(@RequestBody ReplayRequest req) {
        List<ReplayRuleSpec> specs = req.rules().stream()
                .map(r -> new ReplayRuleSpec(r.ruleId(), r.metric(),
                        AlertRule.Operator.valueOf(r.operator()), r.threshold(),
                        r.enabled() == null || r.enabled()))
                .toList();
        return replayService.replay(req.deviceId(), req.fromTs(), req.toTs(), specs);
    }

    /** 试算请求体。 */
    public record ReplayRequest(String deviceId,
                                long fromTs,
                                long toTs,
                                List<RuleSpec> rules) {
    }

    /** 候选规则请求体。 */
    public record RuleSpec(String ruleId,
                           String metric,
                           String operator,
                           double threshold,
                           Boolean enabled) {
    }
}
