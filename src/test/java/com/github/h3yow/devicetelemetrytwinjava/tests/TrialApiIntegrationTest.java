package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 试算能力的端到端 HTTP 验证：上报进入留存 -> 候选规则试算 -> 差异 / 覆盖标记 /
 * 版本与输入值在 JSON 中可见 -> 历史留存只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TrialApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TrialApiIntegrationTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    private void report(long seq, long ts, double temp) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("deviceId", "sim-thermo-001");
        body.put("seq", seq);
        body.put("ts", ts);
        body.put("metrics", Map.of("temperature", temp, "humidity", 50.0));
        body.put("receivedAt", ts);
        mockMvc.perform(post("/api/telemetry/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("HTTP 试算：阈值放宽后差异为 REMOVED，携带两侧规则版本与触发输入值；历史查询只读可见")
    void whatIfOverHttp() throws Exception {
        long base = 1_700_000_000_000L;
        long t1 = base;
        long t2 = base + 1000;
        long t3 = base + 2000;
        long t4 = base + 3000;
        // 当前生效规则 rule-temp-high 为 temperature > 30（BootSeeder 播种在 sim-thermo-001）
        report(800_001, t1, 35.0); // 触发
        report(800_002, t2, 20.0); // 恢复
        report(800_003, t3, 35.0); // 再次触发
        report(800_004, t4, 20.0); // 恢复

        // 候选：阈值放宽到 40 -> 上述两段都不再报
        Map<String, Object> candidate = new HashMap<>();
        candidate.put("deviceId", "sim-thermo-001");
        candidate.put("fromTs", t1);
        candidate.put("toTs", t4);
        candidate.put("rules", List.of(Map.of(
                "ruleId", "rule-temp-high",
                "deviceId", "sim-thermo-001",
                "metric", "temperature",
                "operator", "GT",
                "threshold", 40.0,
                "enabled", true)));

        String resp = mockMvc.perform(post("/api/trials/what-if")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage").value("COMPLETE"))
                .andExpect(jsonPath("$.baseline.ruleSetId").value("baseline"))
                .andExpect(jsonPath("$.candidate.ruleSetId").value("candidate"))
                .andExpect(jsonPath("$.candidate.episodes.length()").value(0))
                .andExpect(jsonPath("$.diffs.length()").value(2))
                .andExpect(jsonPath("$.diffs[0].type").value("REMOVED"))
                .andExpect(jsonPath("$.diffs[0].baselineEpisode.triggerValue").value(35.0))
                .andReturn().getResponse().getContentAsString();

        JsonNode node = MAPPER.readTree(resp);
        log.info("[HTTP 试算] coverage={} baseline段={} candidate段={} 差异类型={}",
                node.path("coverage").asText(),
                node.path("baseline").path("episodes").size(),
                node.path("candidate").path("episodes").size(),
                node.path("diffs").findValuesAsText("type"));

        // 历史留存只读查询：窗口内 4 个点，COMPLETE
        mockMvc.perform(get("/api/trials/history/sim-thermo-001")
                        .param("fromTs", String.valueOf(t1))
                        .param("toTs", String.valueOf(t4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage").value("COMPLETE"))
                .andExpect(jsonPath("$.points.length()").value(4));

        // 非法候选（设备不一致）-> 400
        Map<String, Object> bad = new HashMap<>();
        bad.put("deviceId", "sim-thermo-001");
        bad.put("fromTs", t1);
        bad.put("toTs", t4);
        bad.put("rules", List.of(Map.of(
                "ruleId", "rx", "deviceId", "sim-thermo-002",
                "metric", "temperature", "operator", "GT", "threshold", 1.0, "enabled", true)));
        mockMvc.perform(post("/api/trials/what-if")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());

        // 未知设备 -> 400
        Map<String, Object> unknown = new HashMap<>();
        unknown.put("deviceId", "no-such-device");
        unknown.put("fromTs", t1);
        unknown.put("toTs", t4);
        unknown.put("rules", candidate.get("rules"));
        mockMvc.perform(post("/api/trials/what-if")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(unknown)))
                .andExpect(status().isBadRequest());
        log.info("[HTTP 试算端到端验证通过]");
    }
}
