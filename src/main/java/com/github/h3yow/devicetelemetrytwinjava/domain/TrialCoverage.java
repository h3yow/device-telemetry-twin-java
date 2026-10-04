package com.github.h3yow.devicetelemetrytwinjava.domain;

/**
 * 试算窗口的历史覆盖情况，用于明确区分“历史被淘汰”和“确实没有数据/告警”：
 * <ul>
 *   <li>{@link #COMPLETE}：留存历史完整覆盖了请求窗口；</li>
 *   <li>{@link #PARTIAL_EVICTED}：窗口起点早于仍保留的最早数据，部分历史已按容量规则淘汰；</li>
 *   <li>{@link #NO_DATA}：留存是完整的，但窗口内确实没有任何已接受遥测。</li>
 * </ul>
 */
public enum TrialCoverage {
    COMPLETE,
    PARTIAL_EVICTED,
    NO_DATA
}
