package com.github.h3yow.devicetelemetrytwinjava.rule;

/**
 * 告警规则。规则随 revision 不可变：任何修改都产生新 revision，
 * 告警事件记录产生时的 revision，使历史判定可按当时规则复现。
 *
 * @param id           规则标识
 * @param revision     规则版本（从 1 递增）
 * @param deviceId     作用设备
 * @param metric       判定指标
 * @param op           比较运算
 * @param threshold    阈值
 * @param suppressMillis 同一 (device,rule) 抑制窗口：窗口内已触发则不重复触发
 * @param enabled      是否启用（停用规则不参与新判定，但历史事件保留）
 */
public record AlertRule(String id,
                        int revision,
                        String deviceId,
                        String metric,
                        ComparisonOp op,
                        double threshold,
                        long suppressMillis,
                        boolean enabled) {
}
