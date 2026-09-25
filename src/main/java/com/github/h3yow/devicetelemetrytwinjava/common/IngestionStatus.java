package com.github.h3yow.devicetelemetrytwinjava.common;

/**
 * 遥测接入处理结论。各结论互斥、可区分，任何上报都必须得到其中之一，不允许静默丢弃。
 */
public enum IngestionStatus {

    /** 正常接入，已写入影子 reported 并参与告警判定 */
    ACCEPTED,
    /** 正常接入，但检测到序列号跳号（可能有上报丢失），其余同 ACCEPTED */
    ACCEPTED_GAP,
    /** 降级接入：迟到数据（事件时间超出迟滞窗口），仅留痕，不覆盖影子、不参与告警 */
    ACCEPTED_LATE,
    /** 拒绝：重复上报（messageId 相同，或 seq 相同且负载一致） */
    REJECTED_DUPLICATE,
    /** 拒绝：过期写入（seq 落后于已接受位点），不得覆盖较新状态 */
    REJECTED_STALE,
    /** 拒绝：版本冲突（seq 相同但负载不一致） */
    REJECTED_CONFLICT,
    /** 拒绝：非法报文（字段缺失、数值非法、时间戳异常等） */
    REJECTED_INVALID,
    /** 拒绝：设备未注册 */
    REJECTED_UNKNOWN_DEVICE;

    /** 是否被真正接受（含降级接受） */
    public boolean accepted() {
        return this == ACCEPTED || this == ACCEPTED_GAP || this == ACCEPTED_LATE;
    }

    /** 是否写入影子并驱动告警（迟到降级数据不写入） */
    public boolean appliedToShadow() {
        return this == ACCEPTED || this == ACCEPTED_GAP;
    }
}
