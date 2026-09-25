package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 场景五：HTTP 入口端到端——注册设备/规则、上报、并发入口一致性、拒绝结论的状态码区分。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class HttpEntryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HttpEntryIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void full_flow_over_http_is_consistent_and_explainable() throws Exception {
        // 注册设备
        mockMvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"dev-http","allowedMetrics":["temp"]}
                                """))
                .andExpect(status().isOk());

        // 注册规则 temp > 90
        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"r-http","deviceId":"dev-http","metric":"temp",
                                 "op":"GT","threshold":90,"suppressMillis":1000}
                                """))
                .andExpect(status().isOk());

        // 正常上报 -> 200
        String accepted = telemetry(1, 80.0, 200);
        log.info("[HTTP 正常上报响应] {}", accepted);

        // 越限 -> 200，且带一个 FIRE 事件
        MvcResult fireResult = mockMvc.perform(post("/api/telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(telemetryJson(2, 95.0, "m2")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode fireBody = json.readTree(fireResult.getResponse().getContentAsString());
        log.info("[HTTP 越限响应] {}", fireBody);
        org.junit.jupiter.api.Assertions.assertEquals("FIRE",
                fireBody.path("alarmEvents").get(0).path("type").asText());

        // 重复（复用 m2）-> 409
        mockMvc.perform(post("/api/telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(telemetryJson(2, 95.0, "m2")))
                .andExpect(status().isConflict());

        // 更旧 seq -> 409 STALE
        MvcResult staleResult = mockMvc.perform(post("/api/telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(telemetryJson(1, 120.0, "m-stale")))
                .andExpect(status().isConflict())
                .andReturn();
        JsonNode staleBody = json.readTree(staleResult.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals("REJECTED_STALE",
                staleBody.path("ingestion").path("status").asText());
        log.info("[HTTP 过期上报响应] status=409 body={}", staleBody.path("ingestion"));

        // 未知设备 -> 422
        MvcResult unknownResult = mockMvc.perform(post("/api/telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"ghost","metrics":{"temp":1},"timestamp":3,"seq":1,"messageId":"x"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals("REJECTED_UNKNOWN_DEVICE",
                json.readTree(unknownResult.getResponse().getContentAsString())
                        .path("ingestion").path("status").asText());

        // 影子读取：必须停留在 seq=2/temp=95
        MvcResult shadowResult = mockMvc.perform(get("/api/devices/dev-http/shadow"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode shadow = json.readTree(shadowResult.getResponse().getContentAsString());
        log.info("[HTTP 影子快照] {}", shadow);
        org.junit.jupiter.api.Assertions.assertEquals(2, shadow.path("reportedVersion").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(95.0,
                shadow.path("reported").path("temp").asDouble());

        // 期望状态乐观锁：盲写成功；再用过期版本 0 下发 -> 409
        mockMvc.perform(post("/api/devices/dev-http/shadow/desired")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"temp":70.0}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/dev-http/shadow/desired")
                        .param("expectedVersion", "0")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"temp":60.0}
                                """))
                .andExpect(status().isConflict());

        // 告警事件历史
        MvcResult eventsResult = mockMvc.perform(
                        get("/api/alarms/devices/dev-http/rules/r-http/events"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode events = json.readTree(eventsResult.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals("FIRE",
                events.get(0).path("type").asText());
        org.junit.jupiter.api.Assertions.assertEquals(1,
                events.get(0).path("revision").asInt());
        log.info("[HTTP 告警事件] {}", eventsResult.getResponse().getContentAsString());
    }

    private String telemetry(long seq, double temp, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(telemetryJson(seq, temp, "m" + seq)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private static String telemetryJson(long seq, double temp, String messageId) {
        return String.format(
                """
                {"deviceId":"dev-http","metrics":{"temp":%s},"timestamp":%d,"seq":%d,"messageId":"%s"}
                """,
                temp, 1_000_000 + seq * 1_000, seq, messageId);
    }
}
