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
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：REST -> 遥测管道 -> 影子 -> 告警 的完整本地链路验证。
 * 为避免与 BootSeeder 播种的状态相互干扰，使用独立设备（先注册规则）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EndToEndIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EndToEndIntegrationTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    private String report(String deviceId, long seq, long ts, Map<String, Double> metrics) throws Exception {
        long now = System.currentTimeMillis();
        Map<String, Object> body = new HashMap<>();
        body.put("deviceId", deviceId);
        body.put("seq", seq);
        body.put("ts", ts <= 0 ? now : ts);
        body.put("metrics", metrics);
        body.put("receivedAt", now);
        String resp = mockMvc.perform(post("/api/telemetry/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = MAPPER.readTree(resp);
        log.info("[HTTP 上报] {} -> status={} reason={}", body,
                node.path("result").path("status").asText(),
                node.path("result").path("reason").asText());
        return resp;
    }

    @Test
    @DisplayName("完整链路：注册规则 -> 越限触发 -> 重传 DUPLICATE -> desired 冲突/重试 -> 恢复 -> 历史可查")
    void fullPipelineOverHttp() throws Exception {
        // 使用 BootSeeder 已播种的设备 sim-thermo-002，并为其配置专属规则
        String ruleBody = """
                {"ruleId":"e2e-rule","deviceId":"sim-thermo-002","metric":"temperature",
                 "operator":"GT","threshold":30.0,"enabled":true}
                """;
        mockMvc.perform(put("/api/alerts/rules").contentType(MediaType.APPLICATION_JSON).content(ruleBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value(1));

        long now = System.currentTimeMillis();
        // 越限触发
        JsonNode trigger = MAPPER.readTree(report("sim-thermo-002", 900_001, now,
                Map.of("temperature", 40.0, "humidity", 50.0)));
        assertEquals2("ACCEPTED", trigger.path("result").path("status").asText());
        assertEquals2("TRIGGERED",
                trigger.path("evaluations").get(0).path("verdict").asText());

        // 重传相同 seq -> DUPLICATE
        JsonNode dup = MAPPER.readTree(report("sim-thermo-002", 900_001, now,
                Map.of("temperature", 40.0, "humidity", 50.0)));
        assertEquals2("DUPLICATE", dup.path("result").path("status").asText());

        // 只有一条告警
        mockMvc.perform(get("/api/alerts/devices/sim-thermo-002"))
                .andExpect(status().isOk());

        // desired 乐观冲突演示
        mockMvc.perform(put("/api/shadows/sim-thermo-002/desired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":999,"desired":{"targetTemp":20.0}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERSION_CONFLICT"));

        // 不带版本号写入成功
        mockMvc.perform(put("/api/shadows/sim-thermo-002/desired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"desired":{"targetTemp":20.0}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));

        // 回落到阈值内 -> 恢复
        JsonNode resolved = MAPPER.readTree(report("sim-thermo-002", 900_002, now + 1000,
                Map.of("temperature", 20.0, "humidity", 50.0)));
        assertEquals2("RESOLVED",
                resolved.path("evaluations").get(0).path("verdict").asText());

        // 历史可查且自洽
        mockMvc.perform(get("/api/alerts/history/rules/e2e-rule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 影子快照自洽
        mockMvc.perform(get("/api/shadows/sim-thermo-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reported.temperature").value(20.0))
                .andExpect(jsonPath("$.desired.targetTemp").value(20.0))
                .andExpect(jsonPath("$.reportedVersion").value(2));
        log.info("[端到端链路验证通过]");
    }

    private static void assertEquals2(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
