package com.github.h3yow.devicetelemetrytwinjava.web;

import com.github.h3yow.devicetelemetrytwinjava.domain.TelemetryHistoryWindow;
import com.github.h3yow.devicetelemetrytwinjava.domain.TrialRule;
import com.github.h3yow.devicetelemetrytwinjava.domain.WhatIfResult;
import com.github.h3yow.devicetelemetrytwinjava.service.TrialService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规则试算入口：在留存历史上回放候选规则，并与当前生效规则对照差异。
 * 全部为只读操作，不影响线上影子、告警与判定历史。
 */
@RestController
@RequestMapping("/api/trials")
public class TrialController {

    private final TrialService trialService;

    public TrialController(TrialService trialService) {
        this.trialService = trialService;
    }

    /**
     * 规则试算。请求体形如：
     * <pre>{"deviceId":"sim-thermo-001","fromTs":1727000000000,"toTs":1727003600000,
     * "rules":[
     *   {"ruleId":"rule-temp-high","deviceId":"sim-thermo-001",
     *    "metric":"temperature","operator":"GT","threshold":40.0,"enabled":true},
     *   {"ruleId":"rule-temp-high-2","deviceId":"sim-thermo-001",
     *    "metric":"humidity","operator":"LT","threshold":10.0,"enabled":true}
     * ]}</pre>
     * 响应包含 baseline / candidate 两侧回放、差异列表与历史覆盖情况（COMPLETE /
     * PARTIAL_EVICTED / NO_DATA）。
     */
    @PostMapping("/what-if")
    public WhatIfResult whatIf(@RequestBody WhatIfRequest req) {
        return trialService.runWhatIf(req.deviceId(), req.fromTs(), req.toTs(), req.rules());
    }

    /** 查询某设备一段时间留存的原始遥测（含覆盖情况与淘汰边界）。 */
    @GetMapping("/history/{deviceId}")
    public TelemetryHistoryWindow history(@PathVariable String deviceId,
                                          @RequestParam long fromTs,
                                          @RequestParam long toTs) {
        return trialService.history(deviceId, fromTs, toTs);
    }

    /** 试算请求体。 */
    public record WhatIfRequest(String deviceId,
                                Long fromTs,
                                Long toTs,
                                java.util.List<TrialRule> rules) {
    }
}
