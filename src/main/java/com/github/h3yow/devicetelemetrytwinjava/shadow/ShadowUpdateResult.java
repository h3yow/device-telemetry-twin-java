package com.github.h3yow.devicetelemetrytwinjava.shadow;

/**
 * 影子写入结论。
 *
 * @param accepted 是否成功
 * @param reason   成功或失败原因（VERSION_CONFLICT / STALE / INVALID_STATE / UNKNOWN_DEVICE / OK）
 * @param state    成功后的影子快照；失败时为当前影子快照（可能为 null）
 */
public record ShadowUpdateResult(boolean accepted, String reason, ShadowState state) {

    public static ShadowUpdateResult ok(ShadowState state) {
        return new ShadowUpdateResult(true, "OK", state);
    }

    public static ShadowUpdateResult rejected(String reason, ShadowState state) {
        return new ShadowUpdateResult(false, reason, state);
    }
}
