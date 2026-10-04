package com.github.h3yow.devicetelemetrytwinjava.tests;

import com.github.h3yow.devicetelemetrytwinjava.domain.HistoryPoint;
import com.github.h3yow.devicetelemetrytwinjava.store.TelemetryHistoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 遥测历史留存：每设备上限、全局上限、最旧先淘汰、窗口查询与最早留存时间。
 */
class TelemetryHistoryStoreTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryHistoryStoreTest.class);

    private static HistoryPoint point(String device, long seq, long receivedAt) {
        return new HistoryPoint(device, seq, receivedAt, Map.of("temperature", 20.0 + seq), receivedAt);
    }

    @Test
    @DisplayName("每设备超限后按最旧先淘汰，最早留存时间随之前移")
    void perDeviceEviction() {
        TelemetryHistoryStore store = new TelemetryHistoryStore(3, 1000);
        for (long i = 1; i <= 5; i++) {
            store.append(point("dev-a", i, i * 1000));
        }
        log.info("[留存] size={} earliest={}", store.sizeOf("dev-a"), store.earliestRetainedTs("dev-a"));
        assertEquals(3, store.sizeOf("dev-a"));
        assertEquals(3000L, store.earliestRetainedTs("dev-a"));
        // 窗口 [1000,2000] 内的点已被淘汰 → 查询为空，但 earliest=3000 能区分“淘汰”与“无数据”
        assertTrue(store.query("dev-a", 1000, 2000).isEmpty());
        assertEquals(3, store.query("dev-a", 1000, 5000).size());
    }

    @Test
    @DisplayName("全局超限后跨设备按全局最旧淘汰，总点数不超过上限")
    void globalEviction() {
        TelemetryHistoryStore store = new TelemetryHistoryStore(100, 4);
        store.append(point("dev-a", 1, 1000));
        store.append(point("dev-b", 1, 2000));
        store.append(point("dev-a", 2, 3000));
        store.append(point("dev-b", 2, 4000));
        store.append(point("dev-b", 3, 5000)); // 触发全局淘汰：dev-a@1000 出局
        log.info("[全局淘汰] total={} devA={} devB={}",
                store.totalSize(), store.sizeOf("dev-a"), store.sizeOf("dev-b"));
        assertEquals(4, store.totalSize());
        assertEquals(1, store.sizeOf("dev-a"));
        assertEquals(3, store.sizeOf("dev-b"));
        assertEquals(3000L, store.earliestRetainedTs("dev-a"));
    }

    @Test
    @DisplayName("窗口查询按 (receivedAt, seq) 升序，无留存设备 earliest 为 null")
    void queryOrderingAndEmpty() {
        TelemetryHistoryStore store = new TelemetryHistoryStore(10, 100);
        store.append(point("dev-a", 2, 2000));
        store.append(point("dev-a", 1, 1000));
        store.append(point("dev-a", 3, 2000)); // 同 receivedAt，按 seq 决胜
        List<HistoryPoint> all = store.query("dev-a", 0, 3000);
        log.info("[查询] {}", all);
        assertEquals(List.of(1L, 2L, 3L), all.stream().map(HistoryPoint::seq).toList());
        assertEquals(2, store.query("dev-a", 1500, 2500).size());
        assertNull(store.earliestRetainedTs("dev-x"));
        assertTrue(store.query("dev-x", 0, 3000).isEmpty());
    }
}
